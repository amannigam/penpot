;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.jobs.store
  "Redis persistence for export jobs.

  A job lives in a hash keyed by its id, holding the whole record as a single
  transit blob, and nothing else: no index, so the keyspace cleans itself. The
  hash carries the same TTL as the exported file, so a job record never
  outlives the resource it points at, and nothing refreshes it once the job has
  settled.

  Any replica can read a job; only the one running it can stop it, hence the
  cancel topic."
  (:require
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.config :as cf]
   [app.redis :as redis]
   [promesa.core :as p]))

(def ^:private cancel-topic "export.job-cancel")

(defn- job-key
  [job-id]
  (redis/->key "export.job." job-id))

(defn- ttl
  []
  (cf/get :export-job-ttl 3600))

(defn persist!
  "Writes the whole job record and refreshes its TTL."
  [{:keys [id state] :as job}]
  (let [jkey (job-key id)]
    (p/do
      (redis/hset! jkey {:data (t/encode-str job) :state state})
      (redis/expire! jkey (ttl))
      job)))

(defn fetch
  "The job record, or nil when unknown or expired."
  [job-id]
  (->> (redis/hgetall (job-key job-id))
       (p/fmap (fn [data]
                 (when-let [blob (get data "data")]
                   (try
                     (t/decode-str blob)
                     (catch :default cause
                       (l/warn :hint "unable to decode job record" :job-id (str job-id) :cause cause)
                       nil)))))))

(defn fetch-all
  "Every job record still in the store, of every replica. Only meant for the
  boot sweep: there is no index, so this walks the keyspace."
  []
  (->> (redis/scan (redis/->key "export.job.*"))
       (p/mcat (fn [keys]
                 (->> (map (fn [k]
                             (->> (redis/hgetall k)
                                  (p/fmap (fn [data]
                                            (when-let [blob (get data "data")]
                                              (try
                                                (t/decode-str blob)
                                                (catch :default _ nil)))))))
                           keys)
                      (p/all))))
       (p/fmap (fn [jobs] (vec (remove nil? jobs))))))

(defn remove!
  [{:keys [id]}]
  (redis/del! (job-key id)))

(defn request-cancel!
  "Asks every replica to cancel `job-id`. Only the one running it will act."
  [job-id]
  (redis/pub! cancel-topic (str job-id)))

(defn on-cancel-request
  "Registers `handler` (fn of the job-id string) for cancel requests. Returns an
  unsubscribe fn."
  [handler]
  (redis/sub! cancel-topic handler))
