;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.gridline.figma.convert
  "Gridline: turn a Figma document into a Penpot file.

  Drives `app.common.files.builder` -- the same builder `@penpot/library`
  compiles from, and therefore the same one the official Figma exporter plugin
  uses. We supply REST JSON where it supplies plugin-API nodes.

  The conversion is knowingly partial. Rather than fail on a node it cannot
  represent, it substitutes a placeholder and counts it, so the caller can
  report what did not come across. A migration that silently drops content is
  worse than one that admits to it."
  (:require
   [app.common.data :as d]
   [app.common.files.builder :as fb]
   [app.common.geom.point :as gpt]
   [app.common.logging :as l]
   [app.common.types.path :as path]
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

;; --- colour ----------------------------------------------------------------

(defn- channel->hex
  [v]
  (let [i (-> (double (or v 0)) (* 255.0) (Math/round) (max 0) (min 255))]
    (format "%02x" i)))

(defn- color->hex
  "Figma colours are 0-1 floats per channel; Penpot wants hex."
  [{:keys [r g b]}]
  (str "#" (channel->hex r) (channel->hex g) (channel->hex b)))

(defn- paint->fill
  [{:keys [type color opacity visible] :as paint}]
  (when (and (not= false visible) (= "SOLID" type))
    (let [alpha (* (d/nilv (:a color) 1.0) (d/nilv opacity 1.0))]
      {:fill-color (color->hex color)
       :fill-opacity alpha})))

(defn- paints->fills
  [paints]
  (into [] (keep paint->fill) paints))

(defn- paints->strokes
  [paints weight]
  (into []
        (keep (fn [paint]
                (when-let [fill (paint->fill paint)]
                  {:stroke-color (:fill-color fill)
                   :stroke-opacity (:fill-opacity fill)
                   :stroke-width (d/nilv weight 1)
                   :stroke-style :solid
                   :stroke-alignment :center})))
        paints))

;; --- geometry --------------------------------------------------------------

(defn- geometry
  "Figma reports absolute coordinates, and Penpot also positions shapes in
  absolute page space -- parenting is expressed through frame-id, not nested
  transforms -- so these carry across directly."
  [{:keys [absoluteBoundingBox]}]
  (let [{:keys [x y width height]} absoluteBoundingBox]
    {:x (d/nilv x 0)
     :y (d/nilv y 0)
     :width (max 1 (d/nilv width 1))
     :height (max 1 (d/nilv height 1))}))

(defn- base-props
  [node]
  (cond-> (merge (geometry node)
                 {:name (d/nilv (:name node) "Unnamed")})
    (some? (:opacity node)) (assoc :opacity (:opacity node))
    (false? (:visible node)) (assoc :hidden true)
    (seq (:fills node)) (assoc :fills (paints->fills (:fills node)))
    (seq (:strokes node)) (assoc :strokes (paints->strokes (:strokes node)
                                                           (:strokeWeight node)))
    (number? (:cornerRadius node)) (assoc :rx (:cornerRadius node)
                                          :ry (:cornerRadius node))))

;; --- text ------------------------------------------------------------------

(defn- text-content
  "Penpot text is a tree: root > paragraph-set > paragraph > text runs. Figma
  gives us the flat string plus one TypeStyle, which is enough for a faithful
  single-style paragraph per line."
  [{:keys [characters style] :as _node} fills]
  (let [font-size (some-> (:fontSize style) (str))
        weight    (some-> (:fontWeight style) (int) (str))
        runs      (fn [line]
                    [(cond-> {:text line
                              :fills (if (seq fills)
                                       fills
                                       [{:fill-color "#000000" :fill-opacity 1}])}
                       font-size (assoc :font-size font-size)
                       weight (assoc :font-weight weight)
                       (:italic style) (assoc :font-style "italic"))])]
    {:type "root"
     :children
     [{:type "paragraph-set"
       :children
       (into []
             (map (fn [line]
                    {:type "paragraph"
                     :key (str (uuid/next))
                     :children (runs line)}))
             (str/split (d/nilv characters "") #"\n"))}]}))

;; --- nodes -----------------------------------------------------------------

(def ^:private board-types #{"FRAME" "COMPONENT" "COMPONENT_SET" "INSTANCE" "SECTION"})

;; Anything Figma can describe as geometry. Requesting the document with
;; ?geometry=paths gives these nodes fillGeometry/strokeGeometry as SVG path
;; data, which Penpot can parse directly -- so they convert properly rather
;; than becoming placeholders.
(def ^:private geometry-types
  #{"VECTOR" "STAR" "REGULAR_POLYGON" "BOOLEAN_OPERATION" "LINE" "ELLIPSE"
    "RECTANGLE"})

(def ^:private placeholder-types
  #{"SLICE" "CONNECTOR" "STICKY" "SHAPE_WITH_TEXT" "WASHI_TAPE" "TABLE"})

(defn- geometry->content
  "Figma path data is in the node's own coordinate space; Penpot positions
  path content in absolute page coordinates, so it is translated by the node
  origin. Several subpaths concatenate into one path string."
  [geoms {:keys [x y]}]
  (let [d (->> geoms (keep :path) (remove str/blank?) (str/join " "))]
    (when-not (str/blank? d)
      (-> (path/from-string d)
          (path/move-content (gpt/point x y))))))

(defn- add-path-shape
  "Builds a :path shape from Figma geometry.

  strokeGeometry is already the outlined stroke, so when a node has no fill
  geometry the stroke outline is filled with the stroke colour -- that is what
  makes thin line art (arrows, icons) come through as the shape you drew
  rather than as a block."
  [state node props]
  (let [origin (select-keys props [:x :y])
        fill-content (geometry->content (:fillGeometry node) origin)
        stroke-content (geometry->content (:strokeGeometry node) origin)
        stroke-fills (paints->fills (:strokes node))]
    (cond
      (some? fill-content)
      (fb/add-shape state (-> props
                              (assoc :type :path)
                              (assoc :content fill-content)))

      (some? stroke-content)
      (fb/add-shape state (-> props
                              (assoc :type :path)
                              (assoc :content stroke-content)
                              (assoc :fills (if (seq stroke-fills)
                                              stroke-fills
                                              [{:fill-color "#000000"
                                                :fill-opacity 1}]))
                              (dissoc :strokes)))

      :else nil)))

(declare convert-node)

(defn- convert-children
  [state node]
  (reduce convert-node state (:children node)))

(defn- note-unsupported!
  [state node]
  (update-in state [::report :unsupported (d/nilv (:type node) "UNKNOWN")]
             (fnil inc 0)))

(defn- count-shape
  [state]
  (update-in state [::report :shapes] (fnil inc 0)))

(defn- convert-node
  [state {:keys [type] :as node}]
  (try
    (cond
      (false? (:visible node))
      ;; Hidden in Figma stays hidden rather than absent, so a designer can
      ;; find it again.
      (-> state (fb/add-shape (assoc (base-props node) :type :rect :hidden true)) count-shape)

      (contains? board-types type)
      (-> state
          (fb/add-board (base-props node))
          (convert-children node)
          (fb/close-board)
          (count-shape))

      (= "GROUP" type)
      (let [state (-> state (fb/add-group (base-props node)) (convert-children node))]
        (-> state (fb/close-group) (count-shape)))

      ;; Prefer real geometry when Figma gave us any. A rectangle with a
      ;; corner radius is still better as a rect, so those keep their own
      ;; branch below and only fall here when they carry path data.
      (and (contains? geometry-types type)
           (or (seq (:fillGeometry node)) (seq (:strokeGeometry node)))
           (not (contains? #{"RECTANGLE" "ELLIPSE"} type)))
      (if-let [state' (add-path-shape state node (base-props node))]
        (count-shape state')
        (-> state
            (fb/add-shape (assoc (base-props node) :type :rect))
            count-shape
            (note-unsupported! node)))

      (= "RECTANGLE" type)
      (-> state (fb/add-shape (assoc (base-props node) :type :rect)) count-shape)

      (= "ELLIPSE" type)
      (-> state (fb/add-shape (assoc (base-props node) :type :circle)) count-shape)

      (= "LINE" type)
      (-> state
          (fb/add-shape (-> (base-props node)
                            (assoc :type :rect)
                            (update :height (constantly 1))))
          count-shape)

      (= "TEXT" type)
      (let [props (base-props node)
            fills (:fills props)]
        (-> state
            (fb/add-shape (-> props
                              (assoc :type :text)
                              (dissoc :fills)
                              (assoc :grow-type :fixed)
                              (assoc :content (text-content node fills))))
            count-shape))

      (contains? placeholder-types type)
      ;; An outline, not a filled block. Inheriting the original fill made
      ;; unconvertible art import as solid rectangles, which reads as a broken
      ;; import rather than a gap. Counted, so the caller can say so too.
      (-> state
          (fb/add-shape (-> (base-props node)
                            (assoc :type :rect)
                            (assoc :fills [])
                            (assoc :strokes [{:stroke-color "#b1b2b5"
                                              :stroke-opacity 1
                                              :stroke-width 1
                                              :stroke-style :dotted
                                              :stroke-alignment :center}])))
          count-shape
          (note-unsupported! node))

      :else
      (-> state (convert-children node) (note-unsupported! node)))

    (catch Throwable cause
      ;; One malformed node must not lose the rest of the file.
      (l/wrn :hint "skipping figma node" :node-type type :node-name (:name node)
             :cause cause)
      (update-in state [::report :failed] (fnil inc 0)))))

(defn- convert-page
  [state canvas]
  (-> state
      (fb/add-page {:name (d/nilv (:name canvas) "Page")})
      (convert-children canvas)
      (fb/close-page)))

(defn document->file
  "Build a Penpot file from a Figma document.

  Returns {:file <file> :report {...}}. The report is not decoration: this
  converter is partial by design and the caller shows the user what it could
  not represent."
  [{:keys [document name]} {:keys [project-id file-name]}]
  (let [pages (filter #(= "CANVAS" (:type %)) (:children document))
        state (-> (fb/create-state)
                  (fb/add-file {:name (or file-name name "Figma import")
                                :project-id project-id}))
        state (reduce convert-page state pages)
        state (fb/close-file state)
        file  (-> state ::fb/files vals first)]

    {:file file
     :report (-> (::report state)
                 (assoc :pages (count pages))
                 (update :shapes (fnil identity 0)))}))
