;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.handlers.export
  "Ties an export request to a job: prepares the work, registers it, hands it to
  the scheduler and keeps the job record in step with the outcome."
  (:require
   [app.common.spec :as us]
   [app.handlers.export-frames :as export-frames]
   [app.handlers.export-shapes :as export-shapes]
   [app.jobs :as jobs]
   [app.jobs.scheduler :as scheduler]
   [clojure.spec.alpha :as s]
   [promesa.core :as p]))

;; --- PARAMS

(defmulti command-spec :cmd)

(s/def ::cmd ::us/keyword)
(s/def ::wait ::us/boolean)

(defmethod command-spec :export-shapes [_] ::export-shapes/params)
(defmethod command-spec :export-frames [_] ::export-frames/params)

(s/def ::params
  (s/and (s/keys :req-un [::cmd]
                 :opt-un [::wait])
         (s/multi-spec command-spec :cmd)))

(defn conform-params
  [params]
  (us/conform ::params params))

(defn- prepare
  [cmd auth-token params]
  (case cmd
    :export-shapes (export-shapes/prepare auth-token params)
    :export-frames (export-frames/prepare auth-token params)))

(defn- current
  "The job as the lifecycle last left it: `run` receives the record as it was
  when it started, but progress updates happen in between."
  [job]
  (or (jobs/lookup (:id job)) job))

(defn- run-and-track
  [job run]
  (->> (p/do (run job))
       (p/mcat (fn [resource]
                 (->> (jobs/complete! (current job) resource)
                      (p/fmap (constantly resource)))))
       (p/merr (fn [cause]
                 ;; A cancelled job is already in its terminal state; the
                 ;; rejection here is only how the work unwound.
                 (if (jobs/cancelled? (:id job))
                   (p/rejected cause)
                   (->> (jobs/fail! (current job) cause)
                        (p/mcat (fn [_] (p/rejected cause)))))))))

(defn create-job!
  "Returns a promise of `{:job :resource :pending}`. `:pending` resolves with
  the uploaded resource when the export finishes; callers that only need the
  handle can ignore it (but should attach a no-op error handler)."
  [auth-token {:keys [cmd profile-id is-wasm] :as params}]
  (let [{:keys [resource total run]} (prepare cmd auth-token params)]
    (->> (jobs/create! {:profile-id profile-id
                        :cmd cmd
                        :backend (if is-wasm "wasm" "browser")
                        :total total
                        :name (:name resource)
                        :resource-id (:id resource)}
                       (fn [job] (run-and-track job run)))
         (p/fmap (fn [job]
                   (try
                     {:job job
                      :resource resource
                      :pending (scheduler/submit! job)}
                     (catch :default cause
                       (jobs/fail! job cause)
                       (jobs/release! (:id job))
                       (throw cause))))))))
