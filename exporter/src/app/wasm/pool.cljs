;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.wasm.pool
  "Pool of headless render workers.

  Mirrors `app.browser`: a `generic-pool` whose objects happen to be
  `worker_threads` instead of browsers, so acquisition, eviction and the
  acquire timeout behave the same way for both render backends.

  The worker thread runs the same bundle as the main thread; `app.core/start`
  branches on `isMainThread`.

  A worker is expensive to build (it loads and boots its own render-wasm
  module), so workers are pooled across requests rather than spawned per
  export. Set `:wasm-worker-pool-max` to 0 to fall back to rendering in the
  main thread."
  (:require
   ["generic-pool" :as gp]
   ["node:path" :as path]
   ["node:process" :as proc]
   ["node:worker_threads" :as wt]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.config :as cf]
   [promesa.core :as p]))

(l/set-level! :info)

(defonce pool (atom nil))
(defonce ^:private worker-id (atom 0))

(def ^:private ready-timeout-ms 60000)

(defn- idle-timeout-ms
  "How long a render may go silent before the worker is presumed wedged. Reset
  on every message, so a long export keeps its worker as long as it keeps
  reporting objects; only a thread stuck inside Skia, which reports nothing and
  emits no `exit`, runs it out."
  []
  (* 1000 (cf/get :wasm-render-idle-timeout 300)))

(defn- worker-script
  "Workers run this very bundle: `app.core/start` branches on `isMainThread`, so
  there is one build and one artifact to keep in sync."
  []
  (or (cf/get :wasm-worker-script)
      (path/resolve (aget (.-argv proc/default) 1))))

(defn- create-worker
  []
  (p/create
   (fn [resolve reject]
     (let [script (worker-script)
           id     (swap! worker-id inc)
           worker (new wt/Worker script)
           timer  (js/setTimeout
                   (fn []
                     (l/error :hint "render worker did not become ready" :worker-id id)
                     (.terminate ^js worker)
                     (reject (ex/error :type :internal
                                       :code :worker-not-ready
                                       :hint "render worker did not become ready")))
                   ready-timeout-ms)]

       (unchecked-set worker "__id" id)
       (unchecked-set worker "__alive" true)

       (.on ^js worker "error"
            (fn [cause]
              (l/error :hint "render worker error" :worker-id id :cause cause)
              (unchecked-set worker "__alive" false)))

       (.on ^js worker "exit"
            (fn [code]
              (l/info :hint "render worker exited" :worker-id id :code code)
              (unchecked-set worker "__alive" false)))

       (.once ^js worker "message"
              (fn [data]
                (when (= "ready" (unchecked-get data "type"))
                  (js/clearTimeout timer)
                  (l/info :origin "factory" :action "create" :worker-id id)
                  (resolve worker))))))))

(def ^:private worker-pool-factory
  #js {:create create-worker
       :destroy (fn [worker]
                  (l/info :origin "factory" :action "destroy"
                          :worker-id (unchecked-get worker "__id"))
                  (.terminate ^js worker))
       :validate (fn [worker]
                   (p/resolved (true? (unchecked-get worker "__alive"))))})

(defn enabled?
  []
  (some? @pool))

(defn init
  []
  (let [max-workers (cf/get :wasm-worker-pool-max 2)]
    (if (pos? max-workers)
      (let [opts #js {:max max-workers
                      :min (cf/get :wasm-worker-pool-min 1)
                      :testOnBorrow true
                      :evictionRunIntervalMillis 30000
                      :numTestsPerEvictionRun 2
                      :acquireTimeoutMillis (* 1000 (cf/get :wasm-worker-acquire-timeout 300))
                      :idleTimeoutMillis 300000}]
        (l/info :hint "initializing render worker pool" :opts opts)
        (reset! pool (gp/createPool worker-pool-factory opts)))
      (l/info :hint "render worker pool disabled, rendering in main thread"))
    (p/resolved nil)))

(defn stop
  []
  (when-let [pool @pool]
    (l/info :hint "finalizing render worker pool")
    (reset! pool nil)
    (p/do
      (.drain ^js pool)
      (.clear ^js pool))))

(defn- run-on-worker
  "Settles when the worker reports the render finished, failed, or the thread
  went away. That last case matters: a terminated worker (how a cancel stops a
  render mid-Skia) emits `exit` and never `error`, and a promise left pending
  there would keep its pool slot borrowed for the life of the process."
  [^js worker params cancel-buffer on-object]
  (p/create
   (fn [resolve reject]
     (let [timer (volatile! nil)]
       (letfn [(disarm []
                 (when-let [t @timer]
                   (js/clearTimeout t)
                   (vreset! timer nil)))

               (rearm []
                 (disarm)
                 (vreset! timer (js/setTimeout
                                 (fn []
                                   (l/error :hint "render worker went silent, terminating"
                                            :worker-id (unchecked-get worker "__id"))
                                   (cleanup)
                                   ;; Terminating is what frees the pool slot:
                                   ;; the `exit` it raises has no listener left.
                                   (unchecked-set worker "__alive" false)
                                   (.terminate ^js worker)
                                   (reject (ex/error :type :internal
                                                     :code :render-timeout
                                                     :hint "render worker stopped responding")))
                                 (idle-timeout-ms))))

               (cleanup []
                 (disarm)
                 (.off worker "message" on-message)
                 (.off worker "error" on-error)
                 (.off worker "exit" on-exit))

               (on-error [cause]
                 (cleanup)
                 (reject cause))

               (on-exit [code]
                 (cleanup)
                 (reject (ex/error :type :internal
                                   :code :worker-exited
                                   :hint (str "render worker exited with code " code))))

               (on-message [data]
                 (rearm)
                 (case (unchecked-get data "type")
                   ;; A failure while the main thread handles the object (moving
                   ;; the file, appending to the zip) has to end the render too,
                   ;; or nothing ever settles this promise.
                   "object" (try
                              (on-object (t/decode-str (unchecked-get data "payload")))
                              (catch :default cause
                                (cleanup)
                                (reject cause)))
                   "done"   (do (cleanup) (resolve nil))
                   "error"  (do (cleanup)
                                (reject (ex/error :type :internal
                                                  :code (or (some-> (unchecked-get data "code") keyword)
                                                            :wasm-render-error)
                                                  :hint (unchecked-get data "message"))))
                   nil))]

         (.on worker "message" on-message)
         (.once worker "error" on-error)
         (.once worker "exit" on-exit)
         (rearm)
         (.postMessage worker #js {:type "render"
                                   :params (t/encode-str params)
                                   :cancel cancel-buffer}))))))

(defn render
  "Renders `params` on a pooled worker. `on-worker` (optional) is handed the
  acquired worker so the caller can terminate it to abort a render already
  inside Skia, and is handed `nil` the moment this render is done with it.

  That second call is what keeps a cancel from hitting the wrong export: once
  the worker is back in the pool it can belong to somebody else, and a caller
  still holding the reference would terminate their render instead."
  [params on-object {:keys [cancel-buffer on-worker]}]
  (let [pool @pool]
    (letfn [(detach! []
              (when on-worker (on-worker nil)))]
      (->> (p/do (.acquire ^js pool))
           (p/mcat (fn [worker]
                     (->> (p/do
                            ;; Inside the chain that returns the worker: a throw
                            ;; out here would leave it borrowed for good.
                            (when on-worker (on-worker worker))
                            (run-on-worker worker params cancel-buffer on-object))
                          (p/fmap (fn [result]
                                    (detach!)
                                    (.release ^js pool worker)
                                    result))
                          (p/merr (fn [cause]
                                    ;; The module may be aborted or mid-write, and
                                    ;; a terminated worker cannot be reused.
                                    (detach!)
                                    (-> (p/do (.destroy ^js pool worker))
                                        (p/handle (fn [_ _] (p/rejected cause)))))))))))))

(defn terminate!
  [^js worker]
  (when worker
    (unchecked-set worker "__alive" false)
    (.terminate worker)))
