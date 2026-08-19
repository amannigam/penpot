;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.jobs.utils
  "Disk accounting for export jobs.

  Temp files used to be cleaned only by the per-file timer in `app.util.shell`,
  an hour after creation and lost entirely on restart. Here each job owns the
  paths it creates, so they can be dropped as soon as the job settles, the
  volume usage is known before admitting more work, and whatever a crash left
  behind is swept at boot."
  (:require
   ["node:fs/promises" :as fsp]
   ["node:path" :as path]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.util.shell :as sh]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(def ^:private usage-refresh-ms 30000)

(def ^:private managed-prefix "penpot.")

(defonce ^:private tracked (atom {}))
(defonce ^:private usage-bytes (atom 0))
(defonce ^:private usage-timer (atom nil))

(defn track!
  "Registers `path` as owned by `job-id`, so it is removed when the job settles."
  [job-id path]
  (when (and job-id path)
    (swap! tracked update (str job-id) (fnil conj #{}) path))
  path)

(defn- remove-path!
  [path]
  (->> (p/do (fsp/rm path #js {:recursive true :force true}))
       (p/merr (fn [cause]
                 (l/warn :hint "unable to remove job temp file" :path path :cause cause)
                 (p/resolved nil)))))

(defn release!
  "Removes every file the job owns. Called once the job reached a terminal
  state and its result has already been uploaded, so nothing else reads them."
  [job-id]
  (let [k     (str job-id)
        paths (get @tracked k)]
    (swap! tracked dissoc k)
    (if (seq paths)
      (->> (map remove-path! paths)
           (p/all)
           (p/fmap (fn [_]
                     (l/dbg :hint "released job temp files" :job-id k :count (count paths))
                     nil)))
      (p/resolved nil))))

;; --- USAGE

(defn- dir-usage
  [dir]
  (->> (p/do (fsp/readdir dir))
       (p/mcat (fn [entries]
                 (->> (map (fn [entry]
                             (->> (p/do (fsp/stat (path/join dir entry)))
                                  (p/fmap (fn [^js stat] (.-size stat)))
                                  (p/merr (fn [_] (p/resolved 0)))))
                           entries)
                      (p/all))))
       (p/fmap (fn [sizes] (reduce + 0 sizes)))
       (p/merr (fn [cause]
                 (l/warn :hint "unable to compute tempdir usage" :cause cause)
                 (p/resolved 0)))))

(defn- refresh-usage!
  []
  (->> (dir-usage sh/tmpdir)
       (p/fmap (fn [bytes] (reset! usage-bytes bytes)))))

(defn used-mb
  []
  (js/Math.round (/ @usage-bytes 1048576)))

(defn budget-exceeded?
  "True when the temp volume already holds more than the configured budget. The
  figure is refreshed on a timer, so this is a guard against sustained growth,
  not an exact quota."
  []
  (>= (used-mb) (cf/get :export-tempdir-max-mb 4096)))

;; --- STARTUP SWEEP

(defn sweep!
  "Removes managed temp files older than the job TTL. They can only be leftovers
  of a previous process: every live one belongs to a job of this process."
  []
  (let [max-age (* 1000 (cf/get :export-job-ttl 3600))
        now     (js/Date.now)]
    (->> (p/do (fsp/readdir sh/tmpdir))
         (p/mcat (fn [entries]
                   (->> (filter #(str/starts-with? % managed-prefix) entries)
                        (map (fn [entry]
                               (let [fpath (path/join sh/tmpdir entry)]
                                 (->> (p/do (fsp/stat fpath))
                                      (p/mcat (fn [^js stat]
                                                (if (> (- now (inst-ms (.-mtime stat))) max-age)
                                                  (->> (remove-path! fpath)
                                                       (p/fmap (constantly 1)))
                                                  (p/resolved 0))))
                                      (p/merr (fn [_] (p/resolved 0)))))))
                        (p/all))))
         (p/fmap (fn [results]
                   (let [removed (reduce + 0 results)]
                     (when (pos? removed)
                       (l/info :hint "swept orphaned export temp files" :count removed))
                     removed)))
         (p/merr (fn [cause]
                   (l/warn :hint "temp file sweep failed" :cause cause)
                   (p/resolved 0))))))

(defn init
  []
  (p/do
    (sweep!)
    (refresh-usage!)
    (reset! usage-timer (js/setInterval refresh-usage! usage-refresh-ms))
    nil))

(defn stop
  []
  (when-let [timer @usage-timer]
    (js/clearInterval timer)
    (reset! usage-timer nil))
  (p/resolved nil))
