;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.handlers.export-frames
  (:require
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.handlers.export-shapes :refer [prepare-exports]]
   [app.handlers.resources :as rsc]
   [app.jobs :as jobs]
   [app.jobs.utils :as job.utils]
   [app.renderer :as rd]
   [app.util.shell :as sh]
   [cljs.spec.alpha :as s]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(declare ^:private join-pdf)
(declare ^:private move-file)

(s/def ::name ::us/string)
(s/def ::file-id ::us/uuid)
(s/def ::page-id ::us/uuid)
(s/def ::object-id ::us/uuid)
(s/def ::is-wasm ::us/boolean)

(s/def ::export
  (s/keys :req-un [::file-id ::page-id ::object-id ::name]))

(s/def ::exports
  (s/every ::export :kind vector? :min-count 1))

(s/def ::params
  (s/keys :req-un [::exports]
          :opt-un [::name ::is-wasm]))

(defn- count-objects
  [exports]
  (reduce + 0 (map (comp count :objects) exports)))

(defn- check-cancelled!
  [job]
  (when (jobs/cancelled? (:id job))
    (ex/raise :type :internal
              :code :job-cancelled
              :hint "export job was cancelled")))

(defn- run-export
  [job auth-token resource {:keys [exports is-wasm file-id]}]
  (let [rendered (atom [])

        on-object
        (fn [{:keys [path] :as _object}]
          (job.utils/track! (:id job) path)
          (jobs/progress! job (count (swap! rendered conj path))))

        procs
        (->> (seq exports)
             (map (fn [export]
                    (check-cancelled! job)
                    (rd/render (assoc export :is-wasm is-wasm :job-id (:id job)) on-object))))]

    (job.utils/track! (:id job) (:path resource))
    (->> (p/all procs)
         (p/fmap (fn [_] @rendered))
         (p/mcat (partial join-pdf job file-id))
         (p/mcat (partial move-file resource))
         (p/fmap (constantly resource))
         (p/mcat (partial rsc/upload-resource auth-token))
         (p/mcat (fn [resource]
                   (->> (sh/stat (:path resource))
                        (p/fmap #(merge resource %)))))
         (p/fmap (fn [resource] (dissoc resource :path))))))

(defn prepare
  [auth-token {:keys [exports name is-wasm] :as _params}]
  ;; NOTE: we need to have the `:type` prop because the exports
  ;; datastructure preparation uses it for creating the groups.
  (let [exports  (-> (map #(assoc % :type :pdf :scale 1 :suffix "") exports)
                     (prepare-exports auth-token))
        resource (rsc/create :pdf (or name (-> exports first :name)))
        file-id  (-> exports first :file-id)]
    {:resource resource
     :total (count-objects exports)
     :run (fn [job] (run-export job auth-token resource
                                {:exports exports
                                 :is-wasm is-wasm
                                 :file-id file-id}))}))

(defn- join-pdf
  [job file-id paths]
  (p/let [prefix (str/concat "penpot.pdfunite." file-id ".")
          path   (job.utils/track! (:id job) (sh/tempfile :prefix prefix :suffix ".pdf"))]
    (apply sh/run-cmd! "pdfunite" (conj (vec paths) path))
    path))

(defn- move-file
  [{:keys [path] :as resource} output-path]
  (p/do
    (sh/move! output-path path)
    resource))
