;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.rpc.commands.gridline-figma
  "Gridline: the one part of Figma OAuth that cannot live in the browser.

  Everything else about our Figma integration is client-side -- Figma serves
  permissive CORS, so listing files needs no proxy and no stored credentials.
  The token exchange is the exception: Figma requires the app's client secret,
  Base64-encoded into an Authorization header, and a secret in the browser is
  not a secret. So the browser gets the authorization code, hands it here, and
  we do the swap.

  The resulting access token is returned to the caller rather than persisted.
  We hold no Figma credentials for anyone; if the tab is closed the connection
  is simply made again."
  (:require
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.schema :as sm]
   [app.common.uri :as u]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.gridline.figma.client :as figma]
   [app.gridline.figma.convert :as figma.convert]
   [app.http.client :as http]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.projects :as projects]
   [app.db :as db]
   [app.rpc.doc :as-alias doc]
   [app.util.json :as json]
   [app.util.services :as sv]
   [cuerdas.core :as str])
  (:import
   java.util.Base64))

(def ^:private token-uri "https://api.figma.com/v1/oauth/token")

(defn- basic-auth
  "Figma wants the credentials as HTTP Basic, not as body params."
  [client-id client-secret]
  (->> (.getBytes (str client-id ":" client-secret) "UTF-8")
       (.encodeToString (Base64/getEncoder))
       (str "Basic ")))

(def ^:private schema:exchange-code
  [:map {:title "exchange-figma-code"}
   [:code :string]
   [:code-verifier :string]
   [:redirect-uri :string]])

(def ^:private schema:exchange-code-result
  [:map {:title "FigmaToken"}
   [:access-token :string]
   [:expires-in {:optional true} ::sm/int]])

(sv/defmethod ::exchange-figma-code
  "Swap an authorization code for a Figma access token.

  Requires an authenticated Penpot profile: this endpoint spends our client
  secret, so it is not something anonymous callers get to drive."
  {::doc/added "gridline"
   ::sm/params schema:exchange-code
   ::sm/result schema:exchange-code-result}
  [cfg {:keys [code code-verifier redirect-uri]}]
  (let [client-id     (cf/get :figma-client-id)
        client-secret (cf/get :figma-client-secret)]

    (when (or (str/blank? client-id) (str/blank? client-secret))
      (ex/raise :type :restriction
                :code :figma-oauth-not-configured
                :hint "PENPOT_FIGMA_CLIENT_ID / PENPOT_FIGMA_CLIENT_SECRET are not set"))

    (let [params {:redirect_uri redirect-uri
                  :code code
                  :grant_type "authorization_code"
                  :code_verifier code-verifier}
          req    {:method :post
                  :uri token-uri
                  :headers {"content-type" "application/x-www-form-urlencoded"
                            "accept" "application/json"
                            "authorization" (basic-auth client-id client-secret)}
                  :body (u/map->query-string params)}

          ;; A constant, operator-known endpoint rather than anything a user
          ;; supplied, which is the case the SSRF guard exists for. Same
          ;; reasoning as the telemetry task.
          {:keys [status body]} (http/req cfg req {:skip-ssrf-check? true})]

      (if (= status 200)
        (let [data (json/decode body)]
          (l/inf :hint "figma token exchanged"
                 :expires-in (get data :expires_in))
          (d/without-nils
           {:access-token (get data :access_token)
            :expires-in (get data :expires_in)}))

        (do
          (l/wrn :hint "figma token exchange failed"
                 :status status
                 :body body)
          (ex/raise :type :internal
                    :code :unable-to-exchange-figma-code
                    :hint "figma rejected the authorization code"
                    :response-status status))))))


;; ---------------------------------------------------------------------------
;; Import

(def ^:private schema:import-figma-file
  [:map {:title "import-figma-file"}
   [:token :string]
   [:file-key :string]
   [:project-id ::sm/uuid]
   [:name {:optional true} :string]])

(def ^:private schema:import-figma-file-result
  [:map {:title "FigmaImportResult"}
   [:file-id ::sm/uuid]
   [:name :string]
   [:report [:map-of :keyword :any]]])

(sv/defmethod ::import-figma-file
  "Read a Figma file over the REST API and write it as a Gridline file.

  Replaces the plugin round trip for everything the converter understands.
  What it cannot represent is counted and returned rather than dropped
  silently -- see app.gridline.figma.convert."
  {::doc/added "gridline"
   ::sm/params schema:import-figma-file
   ::sm/result schema:import-figma-file-result
   ::db/transaction true}
  [{:keys [::db/conn] :as cfg} {:keys [::rpc/profile-id token file-key project-id name]}]

  ;; The caller supplies a project id; without this they could write a file
  ;; into somebody else's project.
  (projects/check-edition-permissions! conn profile-id project-id)

  (let [document (figma/get-file cfg token file-key)

        {:keys [file report]}
        (figma.convert/document->file document {:project-id project-id
                                                :file-name name})

        file (assoc file :project-id project-id)]

    (l/inf :hint "importing figma file"
           :file-key file-key
           :pages (:pages report)
           :shapes (:shapes report)
           :unsupported (:unsupported report))

    (bfc/save-file! (assoc cfg ::bfc/timestamp (ct/now)) file)

    {:file-id (:id file)
     :name (:name file)
     :report report}))
