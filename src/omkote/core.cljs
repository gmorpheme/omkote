;; Heavily based on GlkOte by Andrew Plotkin <erkyrath@eblong.com>.
;; (http://eblong.com/zarf/glk/glkote.html)

(ns ^{:doc "A simple OM/core.async implementation of an IF window interface a la GlkOte."}
  omkote.core
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [clojure.set :refer [index]]
            [cljs.core.async :refer [put! <! >! chan mix admix unmix]]))

(enable-console-print!)

(def ENTER-KEY 13)

;; State

;; Following the GlkOte spec a LINE_DATA_ARRAY is a sequence of styled
;; spans, represented in full as a vector of maps with keys :style,
;; :text, :hyperlink. However a shorthand can be used in updates which
;; consists of alternating pairs of style / text in an array.

(defn normalise-line-data-array 
  "Normalise 'shorthand' representation [style1 string1 {:style style2 :text string2} style2 string3]
(a line data array) to vector of maps. Also expand [] to a single blank span."
  [lda]
  (if (empty? lda)
    [{:style "" :text ""}]
    (let [chunks (partition-by map? lda)
          convert-seq (fn [s] (for [[style string] (partition 2 s)] {:style style :text string}))
          homogenised-chunks (mapcat
                              (fn [chunk]
                                (if (map? (first chunk))
                                  chunk
                                  (convert-seq chunk)))
                              chunks)]
      (vec homogenised-chunks))))

(defn append-content-paragraphs
  "Joins [{:content LINE_DATA_ARRAY] :append boolean}] to vector of
  LINE_DATA_ARRAYs. Expects line data array to be normalised."
  [current updates]
  {:pre (vector? current)}
  (let [para-groups (partition-by (comp boolean :append) updates)]
    (vec
     (reduce
      (fn [current para-group]
        (if (:append (first para-group))
          ;; a groups of :append true paragraphs
          (let [prologue (or (vec (butlast current)) [])
                old-tail (or (last current) [])
                new-tail (mapcat :content para-group)]
            (conj prologue (concat (last current) new-tail)))

          ;; a group of stand alone paragraphs
          (let [paras (map :content para-group)]
            (concat current paras))))
      current
      para-groups))))

(defn apply-content-update
  "Apply a content update to the window it applies to."
  [{:keys [clear text lines]} w]
  (let [normalise-content (fn [t]
                            (map
                             (fn [u]
                               (update-in u [:content] normalise-line-data-array))
                             t))]
    (case (:type w)
      :buffer (let [w (if clear (assoc w :text []) w)]
                (assoc w :blocks (append-content-paragraphs (:blocks w)
                                                            (normalise-content text))))
      :grid (assoc w :blocks (map :content (sort-by :line (normalise-content lines)))))))

(defn apply-content-updates
  "Apply map of content updates to the window list."
  [content-updates windows]
  (let [update-map (group-by :id content-updates)]
    (vec
     (map
      (fn [w]
        (if-let [[u] (update-map (:id w))]
          (apply-content-update u w)
          w))
      windows))))

(defn apply-input-update
  "Apply an input update map to the window it applies to."
  [u w]
  (assoc w :input (dissoc u :id)))

(defn apply-input-updates
  "Apply a seq of input updates to the window list."
  [input-updates windows]
  (let [input-map (group-by :id input-updates)]
    (vec
     (map
      (fn [w]
        (if-let [[i] (input-map (:id w))]
          (apply-input-update i w)
          w))
      windows))))

(defn apply-update
  "Apply an update message to the game state."
  [{content-updates :content input-updates :input :as msg} g]
  (-> g
      (update-in [:windows] (partial apply-content-updates content-updates))
      (update-in [:windows] (partial apply-input-updates input-updates))))

;; UI

(defn handle-keydown
  "Handle input event in buffer window and send down the window's input channel."
  [e event-channel id owner]
  (when (== (.-which e) ENTER-KEY)
    (when-let [line (.-value (om/get-node owner "user-text"))]
      (put! event-channel {:type :line :id id :value line})
      (set! (.-value (om/get-node owner "user-text")) ""))))

(defn map->js [m]
  (apply js-obj (apply concat (map (fn [[k v]] [(str (name k)) v]) (seq m)))))

(defn render-paragraph
  "Render a paragraph, with embedded input if supplied."
  ([para]
     (apply dom/p nil
            (map #(dom/span #js {:className (str "Style_" (:style %))} (:text %)) para)))
  ([para input handler]
     (apply dom/p nil
            (concat
             (map #(dom/span #js {:className (str "Style_" (:style %) " final-para")} (:text %)) para)
             [(dom/span #js {:className "input-span"}
                        (dom/input #js {:type "text"
                                        :ref "user-text"
                                        :maxLength (or (:maxlen input) 200)
                                        :onKeyDown handler}))]))))

(defn window-component
  "Construct a buffer-window component. Renders the accumulated state
to divs, sending user input to a channel and contains a go loop to
respond to incoming requests from the game."
  
  [window owner opts]

  (reify

    om/IRenderState
    (render-state [_ {:keys [event-channel]}]

      (let [window-classes (str (name (:type window)) "-window window")]

        (dom/div #js {:id (:id window)
                      :className window-classes
                      :style (map->js (:style window))}
                 
                 (apply dom/div #js {:className "content"}
                        
                        ;; glkote embeds the input in the last paragraph...
                        (let [leading (butlast (:blocks window))
                              final (last (:blocks window))]
                          (concat
                           (map render-paragraph leading)
                           [(if-let [i (:input window)]
                               (render-paragraph final i (fn [e] (handle-keydown
                                                                 e
                                                                 event-channel
                                                                 (:id @window)
                                                                 owner)))
                               (render-paragraph final))]))))))))

(defn game-component 
  "Builds a responsive game into the specified gameport element."
  [{:keys [event-channel control-channel] :as game} owner opts]
  (reify

    om/IWillMount
    (will-mount [_]
      (go-loop []
        (when-let [msg (<! control-channel)]
          (om/transact! game #(apply-update msg %))
          (recur))))
    
    om/IDidMount
    (did-mount [_ _]
      (put! event-channel {:type :init}))
    
    om/IRender 
    (render [_]
      (dom/div #js {:className "window-port"}
               (om/build-all window-component (:windows game) {:init-state {:event-channel event-channel}})))))
