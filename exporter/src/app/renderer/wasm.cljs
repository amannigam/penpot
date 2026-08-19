;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.renderer.wasm
  "Main-thread side of the headless renderer.

  The pipeline itself lives in `app.wasm.render` and runs on a pooled worker
  (`app.wasm.pool`), because a render is a long synchronous Skia call: running
  it here would block the http server, the zip streams and every other export
  for its whole duration.

  With the worker pool disabled (`:wasm-worker-pool-max` 0) renders happen in
  this thread instead, serialized through a promise chain, since a single WASM
  design state and one global mem buffer cannot interleave."
  (:require
   [app.jobs :as jobs]
   [app.wasm.pool :as pool]
   [app.wasm.render :as render]
   [promesa.core :as p]))

(defonce ^:private queue (atom (p/resolved nil)))

(defn- enqueue!
  "Runs `thunk` (0-arg, returns a promise) only after all previously enqueued
  work has settled. Returns `thunk`'s promise. A task's failure is isolated:
  it doesn't break the chain for the next task."
  [thunk]
  (let [result (p/handle @queue (fn [_ _] (thunk)))]
    (reset! queue (p/handle result (fn [_ _] nil)))
    result))

(defn- render-in-process
  [{:keys [job-id] :as params} on-object]
  (enqueue!
   (fn []
     (render/render (assoc params :cancelled? #(and (some? job-id) (jobs/cancelled? job-id)))
                    on-object))))

(defn- render-on-worker
  [{:keys [job-id] :as params} on-object]
  (let [signal  (when job-id (jobs/cancel-signal job-id))
        worker* (volatile! nil)]
    (when job-id
      ;; Between objects the worker sees the flag; inside a render only
      ;; terminating the thread stops it. The reference is cleared as soon as
      ;; the pool takes the worker back, so a later cancel cannot terminate a
      ;; worker that is by then rendering somebody else's export.
      (jobs/on-cancel job-id (fn [] (pool/terminate! @worker*))))
    (->> (pool/render params on-object
                      {:cancel-buffer (some-> signal (.-buffer))
                       :on-worker (fn [worker] (vreset! worker* worker))})
         (p/fnly (fn [_ _] (vreset! worker* nil))))))

(defn render
  [params on-object]
  (if (pool/enabled?)
    (render-on-worker params on-object)
    (render-in-process params on-object)))
