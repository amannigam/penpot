;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.onboarding.figma-import
  "Gridline: the onboarding step that brings Figma files across.

  Two halves, because Figma splits them:

  - Discovery is ours. We call Figma's REST API from the browser to list every
    file in a team, so people pick from a checklist instead of remembering what
    they have. See app.main.data.gridline.figma for why this needs no proxy.

  - Conversion is the Penpot Exporter plugin's. Its transformers are written
    against Figma's plugin API -- live nodes, image.getBytesAsync -- which REST
    does not expose, so a file is still exported by running the plugin on it.
    What we can do is make that loop short: deep-link each file, take the zips
    it produces, and tick them off as they land."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.event :as ev]
   [app.main.data.gridline.figma :as figma]
   [app.main.data.profile :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.file-uploader :refer [file-uploader]]
   [app.main.ui.components.link :refer [link*]]
   [app.main.ui.dashboard.import :as udi]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:const plugin-uri
  "https://www.figma.com/community/plugin/1219369440655168734/penpot-exporter")

(defn- finish!
  [label]
  (st/emit! (du/update-profile-props {:gridline-figma-import-viewed true})
            (ev/event {::ev/name "onboarding-step"
                       :label (str "figma-import:" label)})))

;; ---------------------------------------------------------------------------

(mf/defc step*
  {::mf/private true}
  [{:keys [index children]}]
  [:li {:class (stl/css :step)}
   [:span {:class (stl/css :step-index)} index]
   [:> text* {:as "div" :typography t/body-medium :class (stl/css :color-light)}
    children]])

(mf/defc file-row*
  {::mf/private true}
  [{:keys [file selected done busy error on-toggle on-import]}]
  (let [file-key (get file :key)
        name     (get file :name)]
    [:li {:class (stl/css-case :file-row true :file-row-done done)}
     [:label {:class (stl/css :file-label)}
      [:input {:type "checkbox"
               :checked selected
               :on-change #(on-toggle file-key)}]
      [:> text* {:as "span" :typography t/body-medium :class (stl/css :color-light)}
       name]]

     (cond
       done
       [:> text* {:as "span" :typography t/body-small :class (stl/css :file-status-done)}
        (tr "onboarding.figma-import.file-imported")]

       busy
       [:> text* {:as "span" :typography t/body-small :class (stl/css :color-dimmed)}
        (tr "onboarding.figma-import.file-importing")]

       :else
       [:div {:class (stl/css :file-actions)}
        [:> button* {:variant "secondary"
                     :on-click #(on-import file-key name)}
         (tr "onboarding.figma-import.import-file")]
        ;; Kept as a fallback: anything the converter cannot represent is
        ;; still exportable by hand with the plugin.
        [:a {:class (stl/css :link)
             :href (figma/file-uri file-key)
             :target "_blank"
             :rel "noopener noreferrer"}
         (tr "onboarding.figma-import.open-in-figma")]])

     (when error
       [:> text* {:as "div" :typography t/body-small :class (stl/css :error)}
        error])]))


;; ---------------------------------------------------------------------------

(mf/defc figma-import-modal*
  []
  (let [team      (mf/deref refs/team)
        projects  (mf/deref refs/projects)
        team-id   (get team :id)

        ;; Imported files land in Drafts, same as the dashboard's own import.
        ;; The dashboard behind this modal fetches projects, so for the first
        ;; render or two this is nil and the upload button stays disabled.
        project-id
        (mf/with-memo [projects team-id]
          (->> (vals projects)
               (filter #(and (= team-id (:team-id %)) (:is-default %)))
               (first)
               (:id)))

        phase*     (mf/use-state :intro)
        token*     (mf/use-state nil)   ;; obtained via OAuth; never persisted
        team-ref*  (mf/use-state (str/join ", " (figma/configured-team-ids)))
        error*     (mf/use-state nil)
        loading*   (mf/use-state false)
        groups*    (mf/use-state nil)
        selected*  (mf/use-state #{})
        done*      (mf/use-state #{})
        busy*      (mf/use-state #{})     ;; keys currently importing
        failed*    (mf/use-state {})      ;; key -> message

        file-input (mf/use-ref nil)

        all-files
        (mf/with-memo [@groups*]
          (into [] (mapcat :files) @groups*))

        list-files!
        (mf/use-fn
         (mf/deps @team-ref*)
         (fn [token]
           (let [team-ids (figma/parse-team-ids @team-ref*)]
             (cond
               (str/blank? token)
               (reset! error* (tr "onboarding.figma-import.error-no-token"))

               (empty? team-ids)
               (reset! error* (tr "onboarding.figma-import.error-bad-team"))

               :else
               (do
                 (reset! error* nil)
                 (reset! loading* true)
                 (->> (figma/list-team-files token team-ids)
                      (rx/catch (fn [cause]
                                  (reset! loading* false)
                                  (reset! error* (get cause :message))
                                  (rx/empty)))
                      (rx/subs!
                       (fn [groups]
                         (reset! loading* false)
                         (reset! groups* groups)
                         (reset! phase* :files)
                         (st/emit! (ev/event {::ev/name "onboarding-step"
                                              :label "figma-import:listed"}))))))))))

        on-connect
        (mf/use-fn
         (mf/deps list-files! @team-ref*)
         (fn []
           (if (empty? (figma/parse-team-ids @team-ref*))
             (reset! error* (tr "onboarding.figma-import.error-bad-team"))
             (do
               (reset! error* nil)
               (reset! loading* true)
               (figma/open-authorization-popup!
                (fn [{:keys [access-token error detail]}]
                  (if (some? access-token)
                    (do (reset! token* access-token)
                        (list-files! access-token))
                    (do (reset! loading* false)
                        (reset! error*
                                (case error
                                  :popup-blocked  (tr "onboarding.figma-import.error-popup")
                                  :state-mismatch (tr "onboarding.figma-import.error-state")
                                  ;; Show what Figma actually said. A generic
                                  ;; "try again" hides the one useful fact.
                                  (str (tr "onboarding.figma-import.error-connect")
                                       (when (or detail error)
                                         (str " (" (or detail error) ")")))))))))))))

        ;; Read the file over Figma's API and write it as a Gridline file.
        ;; No plugin, no zip, no per-file clicking.
        on-import-file
        (mf/use-fn
         (mf/deps project-id @token*)
         (fn [file-key file-name]
           (when (and project-id @token*)
             (swap! busy* conj file-key)
             (swap! failed* dissoc file-key)
             (->> (figma/import-file {:token @token*
                                      :file-key file-key
                                      :project-id project-id
                                      :name file-name})
                  (rx/subs!
                   (fn [_result]
                     (swap! busy* disj file-key)
                     (swap! done* conj file-key)
                     (st/emit! (ev/event {::ev/name "onboarding-step"
                                          :label "figma-import:file-imported"})))
                   (fn [cause]
                     (swap! busy* disj file-key)
                     (swap! failed* assoc file-key
                            (or (some-> cause ex-data :hint)
                                (ex-message cause)
                                "import failed"))))))))

        on-toggle
        (mf/use-fn
         (fn [file-key]
           (swap! selected* (fn [s] (if (contains? s file-key)
                                      (disj s file-key)
                                      (conj s file-key))))))

        on-select-all
        (mf/use-fn
         (mf/deps all-files)
         (fn []
           (reset! selected* (into #{} (map :key) all-files))))

        import-fn (udi/use-import-file project-id (fn [] nil))

        on-files-selected
        (mf/use-fn
         (mf/deps import-fn all-files)
         (fn [files]
           ;; The exporter names its zip after the Figma file, so a zip can
           ;; usually be matched back to the row it came from and tick it off.
           ;; When it cannot, nothing breaks -- the row just stays unticked.
           (let [uploaded (into #{} (map #(figma/normalize-name (.-name ^js %))) files)
                 matched  (into #{}
                                (comp (filter #(contains? uploaded (figma/normalize-name (:name %))))
                                      (map :key))
                                all-files)]
             (swap! done* into matched))
           (import-fn files)))

        on-upload-click
        (mf/use-fn #(dom/click (mf/ref-val file-input)))

        on-skip (mf/use-fn #(finish! "skipped"))
        on-done (mf/use-fn #(finish! "finished"))]

    [:div {:class (stl/css :modal-overlay)}
     [:div.animated.fade-in {:class (stl/css :modal-container)}

      [:> heading* {:level 1 :typography t/title-large :class (stl/css :color-light)}
       (tr "onboarding.figma-import.title")]

      (case @phase*
        :intro
        [:*
         [:> text* {:as "div" :typography t/body-large :class (stl/css :color-dimmed)}
          (tr "onboarding.figma-import.subtitle")]

         [:ol {:class (stl/css :steps)}
          [:> step* {:index "1"}
           [:*
            (tr "onboarding.figma-import.step-1")
            " "
            ;; link* renders an <a> driven by on-click with no href, so an
            ;; outbound link has to be a plain anchor.
            [:a {:class (stl/css :link)
                 :href plugin-uri
                 :target "_blank"
                 :rel "noopener noreferrer"}
             (tr "onboarding.figma-import.plugin-name")]]]
          [:> step* {:index "2"} (tr "onboarding.figma-import.step-2")]
          [:> step* {:index "3"} (tr "onboarding.figma-import.step-3")]]

         [:> text* {:as "div" :typography t/body-small :class (stl/css :color-dimmed)}
          (tr "onboarding.figma-import.caveat")]

         [:div {:class (stl/css :actions)}
          [:> button* {:variant "primary"
                       :on-click #(reset! phase* :connect)}
           (tr "onboarding.figma-import.list-my-files")]
          [:> button* {:variant "secondary"
                       :disabled (nil? project-id)
                       :on-click on-upload-click}
           (tr "onboarding.figma-import.choose-files")]
          [:> link* {:class (stl/css :link) :action on-skip}
           (tr "onboarding.figma-import.skip")]]]

        :connect
        [:*
         [:> text* {:as "div" :typography t/body-large :class (stl/css :color-dimmed)}
          (tr "onboarding.figma-import.connect-desc")]

         ;; Figma exposes no way to discover a person's teams, so the id has
         ;; to come from somewhere. When an admin has set it instance-wide,
         ;; nobody else should be asked.
         (when (empty? (figma/configured-team-ids))
           [:div {:class (stl/css :field)}
            [:label {:class (stl/css :field-label)}
             (tr "onboarding.figma-import.team-label")]
            [:input {:class (stl/css :field-input)
                     :type "text"
                     :auto-complete "off"
                     :placeholder "https://www.figma.com/files/team/123456789/..."
                     :value @team-ref*
                     :on-change #(reset! team-ref* (dom/get-target-val %))}]
            [:> text* {:as "div" :typography t/body-small :class (stl/css :color-dimmed)}
             (tr "onboarding.figma-import.team-help")]])

         (when-let [error @error*]
           [:> text* {:as "div" :typography t/body-small :class (stl/css :error)}
            error])

         [:div {:class (stl/css :actions)}
          [:> button* {:variant "primary"
                       :disabled @loading*
                       :on-click on-connect}
           (if @loading*
             (tr "onboarding.figma-import.listing")
             (tr "onboarding.figma-import.connect"))]
          [:> link* {:class (stl/css :link) :action #(reset! phase* :intro)}
           (tr "labels.cancel")]]]

        :files
        [:*
         [:> text* {:as "div" :typography t/body-medium :class (stl/css :color-dimmed)}
          (str (count @done*) " / " (count @selected*) " "
               (tr "onboarding.figma-import.progress"))]

         [:div {:class (stl/css :file-groups)}
          (for [{:keys [team project files error]} @groups*]
            [:div {:key (str (:id team) "/" (:id project)) :class (stl/css :file-group)}
             [:> heading* {:level 2 :typography t/headline-small :class (stl/css :color-light)}
              (if (:name team)
                (str (:name team) " / " (or (:name project) "—"))
                (or (:name project) (str "Team " (:id team))))]
             (when error
               [:> text* {:as "div" :typography t/body-small :class (stl/css :error)}
                (str (tr "onboarding.figma-import.team-unavailable") " " error)])
             (if (seq files)
               [:ul {:class (stl/css :file-list)}
                (for [file files]
                  [:> file-row* {:key (str (:key file))
                                 :file file
                                 :selected (contains? @selected* (:key file))
                                 :done (contains? @done* (:key file))
                                 :busy (contains? @busy* (:key file))
                                 :error (get @failed* (:key file))
                                 :on-toggle on-toggle
                                 :on-import on-import-file}])]
               [:> text* {:as "div" :typography t/body-small :class (stl/css :color-dimmed)}
                (tr "onboarding.figma-import.empty-project")])])]

         [:> text* {:as "div" :typography t/body-small :class (stl/css :color-dimmed)}
          (tr "onboarding.figma-import.drafts-note")]

         [:div {:class (stl/css :actions)}
          [:> button* {:variant "secondary" :on-click on-select-all}
           (tr "onboarding.figma-import.select-all")]
          [:> button* {:variant "primary"
                       :disabled (nil? project-id)
                       :on-click on-upload-click}
           (tr "onboarding.figma-import.choose-files")]
          [:> link* {:class (stl/css :link) :action on-done}
           (tr "onboarding.figma-import.done")]]])

      [:> text* {:as "div" :typography t/body-small :class (stl/css :color-dimmed)}
       (tr "onboarding.figma-import.later")]

      ;; Hidden uploader. This mirrors udi/import-form* but keeps the file
      ;; names, which is what lets an imported zip tick its row off.
      (when (some? project-id)
        [:form {:class (stl/css :hidden-form) :aria-hidden "true"}
         [:& file-uploader {:accept ".penpot,.zip"
                            :multi true
                            :ref file-input
                            :on-selected on-files-selected}]])]]))
