;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.onboarding.figma-import
  "Penpotter: the onboarding step that brings Figma files across.

  Figma exposes no bulk export, so the migration is necessarily driven by a
  human running the Penpot Exporter plugin once per Figma file. The point of
  this step is that nobody has to go and discover that on their own: we name
  the plugin, link to it, spell out the export, and then take the .zip files
  it produces. The actual import reuses the dashboard import dialog."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.event :as ev]
   [app.main.data.profile :as du]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.link :refer [link*]]
   [app.main.ui.dashboard.import :as udi]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:const plugin-uri
  "https://www.figma.com/community/plugin/1219369440655168734/penpot-exporter")

(defn- mark-step-done!
  [label]
  (st/emit! (du/update-profile-props {:penpotter-figma-import-viewed true})
            (ev/event {::ev/name "onboarding-step"
                       :label (str "figma-import:" label)})))

(mf/defc step*
  {::mf/private true}
  [{:keys [index children]}]
  [:li {:class (stl/css :step)}
   [:span {:class (stl/css :step-index)} index]
   [:> text* {:as "div"
              :typography t/body-medium
              :class (stl/css :color-light)}
    children]])

(mf/defc figma-import-modal*
  []
  (let [team        (mf/deref refs/team)
        projects    (mf/deref refs/projects)
        team-id     (get team :id)

        ;; Imported files land in the team's Drafts, the same place the
        ;; dashboard's own import puts them. Projects are fetched by the
        ;; dashboard rendering behind this modal, so this can be nil for the
        ;; first render or two.
        project-id
        (mf/with-memo [projects team-id]
          (->> (vals projects)
               (filter #(and (= team-id (:team-id %)) (:is-default %)))
               (first)
               (:id)))

        file-input  (mf/use-ref nil)

        on-import-click
        (mf/use-fn
         (fn []
           (st/emit! (ev/event {::ev/name "onboarding-step"
                                :label "figma-import:choose-files"}))
           (dom/click (mf/ref-val file-input))))

        on-finish-import
        (mf/use-fn
         (fn []
           (mark-step-done! "imported")))

        on-skip
        (mf/use-fn
         (fn []
           (mark-step-done! "skipped")))]

    [:div {:class (stl/css :modal-overlay)}
     [:div.animated.fade-in {:class (stl/css :modal-container)}
      [:> heading* {:level 1
                    :typography t/title-large
                    :class (stl/css :color-light)}
       (tr "onboarding.figma-import.title")]

      [:> text* {:as "div"
                 :typography t/body-large
                 :class (stl/css :color-dimmed)}
       (tr "onboarding.figma-import.subtitle")]

      [:ol {:class (stl/css :steps)}
       [:> step* {:index "1"}
        [:*
         (tr "onboarding.figma-import.step-1")
         " "
         ;; link* is for in-app actions only -- it renders an <a> with an
         ;; on-click and no href -- so an outbound link is a plain anchor.
         [:a {:class (stl/css :link)
              :href plugin-uri
              :target "_blank"
              :rel "noopener noreferrer"}
          (tr "onboarding.figma-import.plugin-name")]]]
       [:> step* {:index "2"} (tr "onboarding.figma-import.step-2")]
       [:> step* {:index "3"} (tr "onboarding.figma-import.step-3")]]

      [:> text* {:as "div"
                 :typography t/body-small
                 :class (stl/css :color-dimmed)}
       (tr "onboarding.figma-import.caveat")]

      [:div {:class (stl/css :actions)}
       [:> button* {:variant "primary"
                    :disabled (nil? project-id)
                    :on-click on-import-click}
        (tr "onboarding.figma-import.choose-files")]

       [:> link* {:class (stl/css :link)
                  :action on-skip}
        (tr "onboarding.figma-import.skip")]]

      [:> text* {:as "div"
                 :typography t/body-small
                 :class (stl/css :color-dimmed)}
       (tr "onboarding.figma-import.later")]

      (when (some? project-id)
        [:> udi/import-form* {:ref file-input
                              :project-id project-id
                              :on-finish-import on-finish-import}])]]))
