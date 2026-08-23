;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.data.gridline.figma
  "Gridline: a small read-only client for Figma's REST API, used to list the
  files somebody might want to migrate.

  This runs in the browser and calls api.figma.com directly. Figma serves
  `access-control-allow-origin: *` and allows the X-Figma-Token header, so no
  proxy is needed -- which is the point: the access token never reaches our
  backend, and we never store it.

  Discovery only. Converting a file is still the Penpot Exporter plugin's job:
  its transformers are written against Figma's *plugin* API (live nodes,
  `image.getBytesAsync`), which the REST API does not expose."
  (:require
   [app.util.http :as http]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

(def ^:const base-uri "https://api.figma.com/v1")

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
                    :headers {"X-Figma-Token" token}
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

(defn list-projects
  "GET /v1/teams/:team-id/projects -> {:name .. :projects [{:id :name} ...]}"
  [token team-id]
  (request token (str "/teams/" team-id "/projects")))

(defn list-project-files
  "GET /v1/projects/:project-id/files -> {:name .. :files [{:key :name ...}]}

  Note this only covers files inside team projects. Drafts do not belong to a
  project, so they will not appear here."
  [token project-id]
  (request token (str "/projects/" project-id "/files")))

(defn list-team-files
  "Every project in a team, each with its files.

  Emits once, with [{:project {:id :name} :files [...]} ...]."
  [token team-id]
  (->> (list-projects token team-id)
       (rx/mapcat (fn [{:keys [projects]}]
                    (if (seq projects)
                      (->> (rx/from projects)
                           (rx/mapcat (fn [project]
                                        (->> (list-project-files token (:id project))
                                             (rx/map (fn [{:keys [files]}]
                                                       {:project project
                                                        :files (vec files)}))))))
                      (rx/of nil))))
       (rx/filter some?)
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
