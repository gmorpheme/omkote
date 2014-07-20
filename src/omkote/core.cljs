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

;; Some simple utilities

(defn while-not 
  "Return function which applies f to its argument until p holds
   of its argument."
  [p f]
  (fn [x]
    (loop [v x]
      (if (p v) v (recur (f x))))))

(defn map-slurper
  "Return function which slurps values from a sequence into a series
   of maps by overlaying a cycle of specified keys and zipping."
  [& keys]
  (let [n (count keys)]
    (fn [s]
      (for [group (partition n s)]
        (zipmap keys group)))))

;;; Game state handling

;; Following the GlkOte spec a LINE_DATA_ARRAY is a sequence of styled
;; spans, represented in full as a vector of maps with keys :style,
;; :text, :hyperlink. However a shorthand can be used in updates which
;; consists of alternating pairs of style / text in an array.

(defn normalise-line-data-array
  "Normalise 'shorthand' representation [style1 string1 {:style
   style2 :text string2} style2 string3] (a *line data array*) to vector
   of maps. Also expand [] to (vector of) a single blank span."
  [lda]
  (if (empty? lda)
    [{:style "" :text ""}]
    (->> (partition-by map? lda)
         (mapcat (while-not (comp map? first)
                            (map-slurper :style :text)))
         (vec))))

(defn append-content-paragraphs
  "Take a content update instruction [{:content LINE_DATA_ARRAY] :append boolean}...]
   and incorporate into vector of line data arrays."
  [current updates]
  {:pre [(vector? current)]
   :post [vector?]}
  (reduce
   (fn [c {:keys [content append]}]
     (if append
       (conj (pop c) (concat (last c) (normalise-line-data-array content)))
       (conj c (normalise-line-data-array content))))
   current
   updates))

;; A buffer window is represented as map of:
;; :id
;; :type :buffer
;; :blocks (vector of line data arrays)
;; :input input information
;;
;; A grid window is represented as map of:
;;
;; :id
;; :type :grid
;; :blocks (vector of strings?)
;; :input input information

;; An update message contains updates for content, input and windows
;; (the latter being dimensions etc.).

(defn update-window-content
  "Apply a content update to the specified window."
  [w {:keys [clear text lines]}]
  (case (:type w)
    
    :buffer
    (assoc w :blocks (append-content-paragraphs (if clear [] (:blocks w)) text))

    :grid
    (assoc w :blocks (->> (sort-by :line lines)
                          (map :content)
                          (map normalise-line-data-array)
                          (vec)))))

(defn update-window-input
  "Apply an input update map to the specified window."
  [u w]
  (assoc w :input (dissoc u :id)))

(defn update-window-geom
  "Apply a window geometry update to the specified window. Geometry
   currently not supported but data is incorporated by merge."
  [u w]
  (merge u (dissoc w :id)))

(defn update-windows
  "Apply a seq of window updates to a vector of windows, reconciling
   update to relevant window by :id and incorporating update using
   updatefn which takes existing window and update and returns new window."
  [content-updates updatefn windows]
  (let [updates-for (group-by :id content-updates)]
    (vec
     (for [w windows]
       (if-let [updates (updates-for (:id w))]
         (reduce updatefn w updates)
         w)))))

;; TODO support disable & specialinput
(defn apply-update-message
  "Apply a full window update map to game windows."
  [game {:keys [content input window disable specialinput]}]
  (update-in game [:windows]
             #(->> %
                   (update-windows content update-window-content)
                   (update-windows input update-window-input)
                   (update-windows window update-window-geom))))

;; game state is map of:
;;
;; :windows vector of windows
;; :error error message TODO how is this cleared?
(defn apply-message [msg game]
  (case (:type msg)
    :update (apply-update-message game msg)
    :retry game ;; TODO support retry
    :pass game
    :error (assoc game :error (:message msg))))

;;; UI

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

  [window owner]

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
  [{:keys [event-channel control-channel] :as game} owner]
  (reify

    om/IWillMount
    (will-mount [_]
      (go-loop []
        (when-let [msg (<! control-channel)]
          (om/transact! game #(apply-message msg %))
          (recur))))

    om/IDidMount
    (did-mount [_]
      (put! event-channel {:type :init}))

    om/IRender
    (render [_]
      (apply dom/div
       #js {:className "window-port"}
       (om/build-all window-component
                     (:windows game)
                     {:init-state {:event-channel event-channel}})))))
