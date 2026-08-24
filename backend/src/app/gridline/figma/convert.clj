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
  "Figma paints the list bottom-up relative to Penpot, so the order is
  reversed -- the plugin does the same. With one fill it makes no difference;
  with several it is the difference between the right and wrong colour on top."
  [paints]
  (into [] (comp (keep paint->fill) (map identity)) (reverse paints)))

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
  "Position and size.

  Taken from absoluteTransform and size, not absoluteBoundingBox. The bounding
  box is the post-rotation envelope: for any rotated or skewed node it is both
  larger than the shape and offset from it, which is why shapes landed in the
  wrong place and the wrong size. The plugin reads
  absoluteTransform[0][2]/[1][2] for the same reason."
  [{:keys [absoluteTransform size absoluteBoundingBox]}]
  (let [tx (get-in absoluteTransform [0 2])
        ty (get-in absoluteTransform [1 2])
        w  (or (:x size) (:width absoluteBoundingBox))
        h  (or (:y size) (:height absoluteBoundingBox))]
    {:x (d/nilv tx (d/nilv (:x absoluteBoundingBox) 0))
     :y (d/nilv ty (d/nilv (:y absoluteBoundingBox) 0))
     :width (max 0.01 (d/nilv w 1))
     :height (max 0.01 (d/nilv h 1))}))

(defn- rotation
  "Figma's affine matrix is [[cos -sin tx] [sin cos ty]], so the rotation is
  atan2 of the first column. Penpot stores degrees."
  [{:keys [absoluteTransform]}]
  (when-let [m absoluteTransform]
    (let [m00 (get-in m [0 0])
          m10 (get-in m [1 0])]
      (when (and (number? m00) (number? m10))
        (let [deg (-> (Math/atan2 (double m10) (double m00))
                      (Math/toDegrees))
              deg (mod (- 360.0 deg) 360.0)]
          (when (> (Math/abs deg) 0.01) deg))))))

(defn- corner-radii
  "Penpot models corners as r1..r4 (top-left, top-right, bottom-right,
  bottom-left). rx/ry -- which is what this used before -- is a different
  attribute entirely, so radii were silently ignored."
  [{:keys [cornerRadius rectangleCornerRadii]}]
  (cond
    (and (vector? rectangleCornerRadii) (= 4 (count rectangleCornerRadii)))
    (let [[tl tr br bl] rectangleCornerRadii]
      {:r1 tl :r2 tr :r3 br :r4 bl})

    (and (number? cornerRadius) (pos? cornerRadius))
    {:r1 cornerRadius :r2 cornerRadius :r3 cornerRadius :r4 cornerRadius}))

(defn- base-props
  [node]
  (cond-> (merge (geometry node)
                 {:name (d/nilv (:name node) "Unnamed")})
    (some? (:opacity node)) (assoc :opacity (:opacity node))
    (false? (:visible node)) (assoc :hidden true)
    (seq (:fills node)) (assoc :fills (paints->fills (:fills node)))
    (seq (:strokes node)) (assoc :strokes (paints->strokes (:strokes node)
                                                           (:strokeWeight node)))
    (some? (rotation node)) (assoc :rotation (rotation node))
    (some? (corner-radii node)) (merge (corner-radii node))))

;; --- text ------------------------------------------------------------------

(defn- font-slug
  "Penpot identifies Google fonts as gfont-<slugified family>, which is how
  the exporter plugin resolves them too."
  [family]
  (-> (or family "")
      (str/lower)
      (str/replace #"[^a-z0-9]+" "-")
      (str/replace #"^-+|-+$" "")))

(defn- line-height
  "Penpot stores line height as a ratio of the font size, not pixels."
  [{:keys [lineHeightPx lineHeightPercentFontSize fontSize]}]
  (cond
    (and (number? lineHeightPx) (number? fontSize) (pos? fontSize))
    (str (/ (double lineHeightPx) (double fontSize)))

    (number? lineHeightPercentFontSize)
    (str (/ (double lineHeightPercentFontSize) 100.0))

    :else "1.2"))

(defn- letter-spacing
  [{:keys [letterSpacing]}]
  (str (d/nilv letterSpacing 0)))

(defn- text-align
  [{:keys [textAlignHorizontal]}]
  (case textAlignHorizontal
    "RIGHT" "right"
    "CENTER" "center"
    "JUSTIFIED" "justify"
    "left"))

(defn- vertical-align
  [{:keys [textAlignVertical]}]
  (case textAlignVertical
    "CENTER" "center"
    "BOTTOM" "bottom"
    "top"))

(defn- text-run
  "One styled run. The font family was missing entirely before, which is why
  imported text rendered in Penpot's default face regardless of the design."
  [line style fills]
  (let [family (d/nilv (:fontFamily style) "Source Sans Pro")]
    (cond-> {:text line
             :font-family family
             :font-id (str "gfont-" (font-slug family))
             :font-size (str (d/nilv (:fontSize style) 14))
             :font-weight (str (int (d/nilv (:fontWeight style) 400)))
             :font-style (if (:italic style) "italic" "normal")
             :font-variant-id (if (:italic style) "italic" "regular")
             :line-height (line-height style)
             :letter-spacing (letter-spacing style)
             :fills (if (seq fills)
                      fills
                      [{:fill-color "#000000" :fill-opacity 1}])}
      (= "UPPER" (:textCase style)) (assoc :text-transform "uppercase")
      (= "LOWER" (:textCase style)) (assoc :text-transform "lowercase")
      (= "TITLE" (:textCase style)) (assoc :text-transform "capitalize")
      (= "UNDERLINE" (:textDecoration style)) (assoc :text-decoration "underline")
      (= "STRIKETHROUGH" (:textDecoration style)) (assoc :text-decoration "line-through"))))

(defn- text-content
  "Penpot text is a tree: root > paragraph-set > paragraph > runs. Figma gives
  a flat string plus one TypeStyle, so each line becomes a paragraph carrying
  that style."
  [{:keys [characters style] :as _node} fills]
  (let [align (text-align style)
        valign (vertical-align style)]
    {:type "root"
     :vertical-align valign
     :children
     [{:type "paragraph-set"
       :children
       (into []
             (map (fn [line]
                    {:type "paragraph"
                     :key (str (uuid/next))
                     :text-align align
                     :children [(text-run line style fills)]}))
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
