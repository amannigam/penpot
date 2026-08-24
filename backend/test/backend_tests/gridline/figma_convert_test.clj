;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.gridline.figma-convert-test
  (:require
   [app.common.uuid :as uuid]
   [app.gridline.figma.convert :as sut]
   [clojure.test :as t]))

(def ^:private document
  "A Figma document exercising each branch of the converter: a page, a frame
  with children, a group, every basic shape, text, and an unsupported node."
  {:name "Test file"
   :document
   {:type "DOCUMENT"
    :children
    [{:type "CANVAS"
      :name "Page 1"
      :children
      [{:type "FRAME" :name "Board"
        :absoluteBoundingBox {:x 0 :y 0 :width 400 :height 300}
        :fills [{:type "SOLID" :color {:r 1 :g 1 :b 1 :a 1}}]
        :children
        [{:type "RECTANGLE" :name "Rect"
          :absoluteBoundingBox {:x 10 :y 10 :width 100 :height 50}
          :cornerRadius 4
          :fills [{:type "SOLID" :color {:r 0.2 :g 0.6 :b 0.4 :a 1}}]
          :strokes [{:type "SOLID" :color {:r 0 :g 0 :b 0 :a 1}}]
          :strokeWeight 2}
         {:type "ELLIPSE" :name "Circle"
          :absoluteBoundingBox {:x 120 :y 10 :width 60 :height 60}
          :fills [{:type "SOLID" :color {:r 1 :g 0 :b 0 :a 0.5}}]}
         {:type "GROUP" :name "Group"
          :absoluteBoundingBox {:x 10 :y 80 :width 200 :height 100}
          :children
          [{:type "TEXT" :name "Label"
            :absoluteBoundingBox {:x 10 :y 80 :width 200 :height 40}
            :characters "Hello\nGridline"
            :style {:fontSize 16 :fontWeight 400}
            :fills [{:type "SOLID" :color {:r 0 :g 0 :b 0 :a 1}}]}
           {:type "LINE" :name "Divider"
            :absoluteBoundingBox {:x 10 :y 130 :width 180 :height 0}}]}
         {:type "VECTOR" :name "Icon"
          :absoluteBoundingBox {:x 220 :y 10 :width 24 :height 24}}]}]}
     {:type "CANVAS" :name "Page 2" :children []}]}})

(t/deftest converts-a-document
  (let [{:keys [file report]} (sut/document->file document {:project-id (uuid/next)})]

    (t/testing "produces a file with both pages"
      (t/is (some? file))
      (t/is (= "Test file" (:name file)))
      (t/is (= 2 (:pages report)))
      (t/is (= 2 (count (get-in file [:data :pages])))))

    (t/testing "every shape lands on the page"
      (let [page-id (first (get-in file [:data :pages]))
            objects (get-in file [:data :pages-index page-id :objects])
            by-name (into {} (map (juxt :name identity)) (vals objects))]

        (t/is (contains? by-name "Board"))
        (t/is (contains? by-name "Rect"))
        (t/is (contains? by-name "Circle"))
        (t/is (contains? by-name "Group"))
        (t/is (contains? by-name "Label"))

        (t/testing "types map correctly"
          (t/is (= :frame (get-in by-name ["Board" :type])))
          (t/is (= :rect  (get-in by-name ["Rect" :type])))
          (t/is (= :circle (get-in by-name ["Circle" :type])))
          (t/is (= :group (get-in by-name ["Group" :type])))
          (t/is (= :text  (get-in by-name ["Label" :type]))))

        (t/testing "geometry carries across"
          (let [rect (get by-name "Rect")]
            (t/is (= 10 (:x rect)))
            (t/is (= 100 (:width rect)))
            (t/is (= 50 (:height rect)))))

        (t/testing "colours convert from 0-1 floats to hex"
          (let [fill (first (:fills (get by-name "Rect")))]
            (t/is (= "#3399" (subs (:fill-color fill) 0 5)))))

        (t/testing "half-transparent fill keeps its opacity"
          (let [fill (first (:fills (get by-name "Circle")))]
            (t/is (= 0.5 (:fill-opacity fill)))))

        (t/testing "strokes convert"
          (let [stroke (first (:strokes (get by-name "Rect")))]
            (t/is (= 2 (:stroke-width stroke)))))

        (t/testing "text becomes a paragraph per line"
          (let [content (:content (get by-name "Label"))
                paras (get-in content [:children 0 :children])]
            (t/is (= "root" (:type content)))
            (t/is (= 2 (count paras)))
            (t/is (= "Hello" (get-in paras [0 :children 0 :text])))
            (t/is (= "Gridline" (get-in paras [1 :children 0 :text])))))

        (t/testing "children are parented to the board"
          (let [board (get by-name "Board")
                rect  (get by-name "Rect")]
            (t/is (= (:id board) (:frame-id rect)))))))

    (t/testing "unsupported nodes are reported, not dropped silently"
      (t/is (= 1 (get-in report [:unsupported "VECTOR"])))
      (t/is (nil? (:failed report))))))

(def ^:private vector-document
  "Figma returns fillGeometry/strokeGeometry only when the document is
  requested with ?geometry=paths, which the client does."
  {:name "Vectors"
   :document
   {:type "DOCUMENT"
    :children
    [{:type "CANVAS" :name "Art"
      :children
      [{:type "VECTOR" :name "Filled"
        :absoluteBoundingBox {:x 100 :y 50 :width 20 :height 20}
        :fills [{:type "SOLID" :color {:r 0 :g 0 :b 0 :a 1}}]
        :fillGeometry [{:path "M0 0 L20 0 L10 20 Z" :windingRule "NONZERO"}]}
       {:type "VECTOR" :name "Stroked"
        :absoluteBoundingBox {:x 200 :y 60 :width 30 :height 4}
        :strokes [{:type "SOLID" :color {:r 1 :g 0 :b 0 :a 1}}]
        :strokeGeometry [{:path "M0 0 L30 0 L30 4 L0 4 Z"}]}
       {:type "SLICE" :name "Slice"
        :absoluteBoundingBox {:x 0 :y 0 :width 5 :height 5}}]}]}})

(t/deftest converts-vector-geometry
  (let [{:keys [file report]} (sut/document->file vector-document
                                                  {:project-id (uuid/next)})
        page-id (first (get-in file [:data :pages]))
        objects (get-in file [:data :pages-index page-id :objects])
        by-name (into {} (map (juxt :name identity)) (vals objects))]

    (t/testing "a filled vector becomes a real path, not a placeholder"
      (let [shape (get by-name "Filled")]
        (t/is (= :path (:type shape)))
        (t/is (some? (:content shape)))
        (t/testing "translated from node-local to absolute page coordinates"
          (t/is (= 100.0 (double (get-in shape [:selrect :x]))))
          (t/is (= 50.0 (double (get-in shape [:selrect :y]))))
          (t/is (= 20.0 (double (get-in shape [:selrect :width])))))))

    (t/testing "a stroke-only vector is filled with its stroke colour"
      ;; strokeGeometry is the outlined stroke, so filling it is what makes
      ;; thin line art -- arrows, icons -- render as drawn.
      (let [shape (get by-name "Stroked")]
        (t/is (= :path (:type shape)))
        (t/is (some? (:content shape)))
        (t/is (= "#ff0000" (:fill-color (first (:fills shape)))))))

    (t/testing "geometry-less nodes are outlines, never filled blocks"
      (let [shape (get by-name "Slice")]
        (t/is (= :rect (:type shape)))
        (t/is (empty? (:fills shape)))
        (t/is (seq (:strokes shape)))))

    (t/testing "only the genuinely unconvertible node is reported"
      (t/is (= {"SLICE" 1} (:unsupported report))))))

(def ^:private fidelity-document
  {:name "Fidelity"
   :document
   {:type "DOCUMENT"
    :children
    [{:type "CANVAS" :name "P"
      :children
      [{:type "RECTANGLE" :name "Rotated"
        ;; the bounding box deliberately disagrees with the transform
        :absoluteTransform [[0.7071 -0.7071 100] [0.7071 0.7071 200]]
        :size {:x 50 :y 20}
        :absoluteBoundingBox {:x 80 :y 190 :width 99 :height 99}
        :cornerRadius 8}
       {:type "RECTANGLE" :name "Corners"
        :absoluteTransform [[1 0 10] [0 1 20]]
        :size {:x 40 :y 40}
        :rectangleCornerRadii [1 2 3 4]}
       {:type "RECTANGLE" :name "TwoFills"
        :absoluteTransform [[1 0 0] [0 1 0]] :size {:x 10 :y 10}
        :fills [{:type "SOLID" :color {:r 1 :g 0 :b 0 :a 1}}
                {:type "SOLID" :color {:r 0 :g 0 :b 1 :a 1}}]}
       {:type "TEXT" :name "Styled"
        :absoluteTransform [[1 0 5] [0 1 6]] :size {:x 200 :y 30}
        :characters "Hello"
        :style {:fontFamily "Sora" :fontSize 24 :fontWeight 700
                :lineHeightPx 36 :letterSpacing 1.5
                :textAlignHorizontal "CENTER" :textCase "UPPER"}
        :fills [{:type "SOLID" :color {:r 0 :g 0 :b 0 :a 1}}]}]}]}})

(t/deftest matches-figma-geometry-and-styling
  (let [{:keys [file]} (sut/document->file fidelity-document {:project-id (uuid/next)})
        page-id (first (get-in file [:data :pages]))
        objects (get-in file [:data :pages-index page-id :objects])
        by-name (into {} (map (juxt :name identity)) (vals objects))]

    (t/testing "position and size come from the transform, not the bounding box"
      ;; absoluteBoundingBox is the post-rotation envelope, so for a rotated
      ;; node it is both larger and offset. Using it put shapes in the wrong
      ;; place at the wrong size.
      (let [shape (get by-name "Rotated")]
        (t/is (= 100.0 (double (:x shape))))
        (t/is (= 200.0 (double (:y shape))))
        (t/is (= 50.0 (double (:width shape))))
        (t/is (= 20.0 (double (:height shape))))))

    (t/testing "rotation is derived from the transform"
      (t/is (= 315 (int (:rotation (get by-name "Rotated"))))))

    (t/testing "corner radii use r1..r4, uniform and per-corner"
      (let [uniform (get by-name "Rotated")
            corners (get by-name "Corners")]
        (t/is (= [8 8 8 8] (mapv #(get uniform %) [:r1 :r2 :r3 :r4])))
        (t/is (= [1 2 3 4] (mapv #(get corners %) [:r1 :r2 :r3 :r4])))))

    (t/testing "fills are reversed, because Figma stacks them the other way"
      (t/is (= ["#0000ff" "#ff0000"]
               (mapv :fill-color (:fills (get by-name "TwoFills"))))))

    (t/testing "text carries its actual font, not Penpot's default"
      (let [run (get-in (get by-name "Styled")
                        [:content :children 0 :children 0 :children 0])]
        (t/is (= "Sora" (:font-family run)))
        (t/is (= "gfont-sora" (:font-id run)))
        (t/is (= "700" (:font-weight run)))
        (t/is (= "24" (:font-size run)))
        (t/testing "line height is a ratio of font size, not pixels"
          (t/is (= "1.5" (:line-height run))))
        (t/is (= "uppercase" (:text-transform run)))))

    (t/testing "paragraph alignment carries"
      (t/is (= "center" (get-in (get by-name "Styled")
                                [:content :children 0 :children 0 :text-align]))))))
