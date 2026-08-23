;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.gridline.figma
  "Gridline: a small read-only client for Figma's REST API, used to list the
  files somebody might want to migrate.

  This runs in the browser and calls api.figma.com directly. Figma serves
  `access-control-allow-origin: *` and permits the Authorization header, so no
  proxy is needed -- which is the point: the access token never reaches our
  backend, and we never store it.

  Discovery only. Converting a file is still the Penpot Exporter plugin's job:
  its transformers are written against Figma's *plugin* API (live nodes,
  `image.getBytesAsync`), which the REST API does not expose."
  (:require
   [app.config :as cf]
   [app.main.repo :as rp]
   [app.util.http :as http]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

(def ^:const base-uri "https://api.figma.com")

(defn parse-team-id
  "Accepts a bare team id or a pasted Figma team URL.

  Figma's docs are explicit that team ids cannot be obtained programmatically
  -- you read them out of the browser URL -- so people will paste whatever
  they have."
  [input]
  (let [input (str/trim (or input ""))]
    (cond
      (str/blank? input) nil
      (re-matches #"\d+" input) input
      :else (second (re-find #"/team/(\d+)" input)))))

(defn parse-team-ids
  "Several teams, comma or whitespace separated. Figma has no endpoint that
  lists them, so each one has to be named explicitly."
  [input]
  (->> (str/split (or input "") #"[,\s]+")
       (map parse-team-id)
       (filter some?)
       (distinct)
       (vec)))

(defn- error-message
  [status body]
  (or (get body :err)
      (get body :message)
      (case status
        403 "Figma rejected that token. Check it has not expired."
        404 "Figma has no team with that id, or this token cannot see it."
        429 "Figma is rate limiting us. Wait a moment and try again."
        (str "Figma returned an unexpected status (" status ")."))))

(defn- request
  [token path]
  (->> (http/send! {:method :get
                    :uri (str base-uri path)
                    ;; OAuth access tokens go in an Authorization header.
                    ;; X-Figma-Token is only for personal access tokens -- it
                    ;; was a leftover from that flow, and Figma answers
                    ;; "Invalid token" when an OAuth token arrives that way.
                    :headers {"Authorization" (str "Bearer " token)}
                    ;; Penpot's default headers exist for our own API; none of
                    ;; them belong in a third-party request.
                    :omit-default-headers true
                    :credentials "omit"
                    :response-type :json})
       (rx/mapcat
        (fn [{:keys [status body] :as response}]
          (let [body (js->clj body :keywordize-keys true)]
            (if (http/success? response)
              (rx/of body)
              (rx/throw {:type :figma-api
                         :status status
                         :message (error-message status body)})))))))

(defn list-folders
  "GET /v2/teams/:team-id/folders -> {:name .. :folders [{:id :name} ...]}

  Figma renamed projects to folders. The v1 equivalent still exists but
  requires projects:read, which cannot be granted to a new OAuth app any more,
  so v2 is the only route that works."
  [token team-id]
  (request token (str "/v2/teams/" team-id "/folders")))

(defn list-folder-files
  "GET /v2/folders/:folder-id/files -> {:name .. :files [{:key :name ...}]}

  Only covers files inside team folders. Drafts do not live in a folder, so
  they never appear here."
  [token folder-id]
  (request token (str "/v2/folders/" folder-id "/files")))

(defn- list-one-team
  [token team-id]
  (->> (list-folders token team-id)
       (rx/mapcat (fn [{:keys [name folders]}]
                    (if (seq folders)
                      (->> (rx/from folders)
                           (rx/mapcat (fn [folder]
                                        (->> (list-folder-files token (:id folder))
                                             (rx/map (fn [{:keys [files]}]
                                                       {:team {:id team-id :name name}
                                                        :project folder
                                                        :files (vec files)}))))))
                      (rx/of {:team {:id team-id :name name}
                              :project nil
                              :files []}))))
       ;; One inaccessible team must not blank the whole list: somebody can
       ;; easily be a member of one team and not another (Figma answers 403
       ;; "Permission denied"), and the useful thing is to show what they can
       ;; see and name what they cannot.
       (rx/catch (fn [cause]
                   (rx/of {:team {:id team-id}
                           :error (or (:message cause) "unavailable")
                           :files []})))))

(defn list-team-files
  "Every folder in every given team, each with its files.

  Emits once, with [{:team {..} :project {..} :files [...]} ...]. Entries that
  failed carry :error instead of a project."
  [token team-ids]
  (->> (rx/from (vec team-ids))
       (rx/mapcat (partial list-one-team token))
       (rx/reduce conj [])))

(defn file-uri
  "Deep link back to the Figma file, so the plugin run is one click away."
  [file-key]
  (str "https://www.figma.com/design/" file-key))

(defn normalize-name
  "The exporter names its zip after the Figma file, but not byte-identically
  (spaces, case and punctuation vary). Compare on a squashed form so an
  imported zip can be matched back to the file it came from."
  [s]
  (-> (or s "")
      (str/lower)
      (str/replace #"\.(zip|penpot)$" "")
      (str/replace #"[^a-z0-9]+" "")))

;; ---------------------------------------------------------------------------
;; OAuth
;;
;; The browser drives the authorization step and holds the resulting token; the
;; backend only performs the code-for-token exchange, because Figma requires the
;; client secret for it. PKCE is used as well as the secret, not instead of it.

(def ^:const authorize-uri "https://www.figma.com/oauth")

;; Read-only, and the narrowest set that works. Verified against the live API,
;; because the docs are misleading here: they present folders:read as the
;; successor to projects:read, but the v1 endpoints still demand projects:read
;; -- a scope Figma no longer offers when configuring an app. The v2 endpoints
;; are the ones that actually accept folders:read, so those are what we call.
(def ^:const scopes "folders:read")

(defn- base64url
  [^js buffer]
  (-> (.btoa js/window (.apply js/String.fromCharCode nil (js/Uint8Array. buffer)))
      (str/replace "+" "-")
      (str/replace "/" "_")
      (str/replace "=" "")))

(defn random-verifier
  "A PKCE code verifier: 32 random bytes, base64url encoded."
  []
  (let [bytes (js/Uint8Array. 32)]
    (.getRandomValues js/crypto bytes)
    (base64url (.-buffer bytes))))

(defn code-challenge
  "S256 challenge for a verifier. Async -- crypto.subtle returns a promise --
  so this yields a stream of one value. S256 is the only method Figma accepts."
  [verifier]
  (->> (rx/from (.digest (.-subtle js/crypto) "SHA-256"
                         (.encode (js/TextEncoder.) verifier)))
       (rx/map base64url)))

(defn build-authorize-uri
  [{:keys [redirect-uri state challenge]}]
  (let [params {:client_id cf/figma-client-id
                :redirect_uri redirect-uri
                :scope scopes
                :state state
                :response_type "code"
                :code_challenge challenge
                :code_challenge_method "S256"}]
    (str authorize-uri "?"
         (str/join "&" (map (fn [[k v]]
                              (str (name k) "=" (js/encodeURIComponent (str v))))
                            params)))))

(defn exchange-code
  "Hand the authorization code to our backend, which holds the client secret.
  Returns a stream of {:access-token ... :expires-in ...}."
  [{:keys [code verifier redirect-uri]}]
  (rp/cmd! :exchange-figma-code {:code code
                                 :code-verifier verifier
                                 :redirect-uri redirect-uri}))

(defn configured?
  []
  (not (str/blank? cf/figma-client-id)))

(defn configured-team-ids
  "Figma has no endpoint that lists a user's teams, and no scope for it -- ids
  can only be read out of a browser URL. That is a property of the
  organisation, not of each person, so an admin sets them once via
  PENPOT_FIGMA_TEAM_ID (comma separated for several) and nobody else is asked."
  []
  (parse-team-ids cf/figma-team-id))
