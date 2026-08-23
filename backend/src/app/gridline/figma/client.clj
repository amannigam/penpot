;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.gridline.figma.client
  "Gridline: server-side reads of Figma's REST API.

  The browser already talks to Figma directly for listing files, which needs
  no proxy. Fetching a document is different: the JSON for a real file runs to
  megabytes, it has to be converted and persisted anyway, and images will need
  fetching server-side too. So the token comes to us for this one call."
  (:require
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.http.client :as http]
   [app.util.json :as json]))

(def ^:private base-uri "https://api.figma.com")

(defn- request
  [cfg token path]
  (let [req {:method :get
             :uri (str base-uri path)
             ;; OAuth tokens are Bearer; X-Figma-Token is for personal access
             ;; tokens only and returns "Invalid token" for these.
             :headers {"authorization" (str "Bearer " token)
                       "accept" "application/json"}}
        ;; A constant, operator-known host rather than anything user supplied,
        ;; which is the case the SSRF guard exists for.
        {:keys [status body]} (http/req cfg req {:skip-ssrf-check? true})]

    (if (= 200 status)
      (json/decode body)
      (let [data (ex/ignoring (json/decode body))]
        (l/wrn :hint "figma api call failed" :path path :status status)
        (ex/raise :type :validation
                  :code :figma-request-failed
                  :hint (or (get data :err)
                            (get data :message)
                            (str "Figma returned status " status))
                  :response-status status)))))

(defn get-file
  "GET /v1/files/:key -- the whole document tree.

  Requires the file_content:read scope. `depth` is deliberately not set: we
  want every node, and Figma returns the full tree by default."
  [cfg token file-key]
  (l/inf :hint "fetching figma file" :file-key file-key)
  (request cfg token (str "/v1/files/" file-key)))

(defn get-file-meta
  "Cheap metadata call, used to name the file and to fail early on a bad key
  or a missing scope before pulling megabytes of document."
  [cfg token file-key]
  (request cfg token (str "/v1/files/" file-key "?depth=1")))
