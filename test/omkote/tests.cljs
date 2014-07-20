(ns omkote.tests
  (:require-macros [cemerick.cljs.test :refer [is deftest with-test run-tests testing test-var]])
  (:require [omkote.core :as omkote]
            [cemerick.cljs.test :as t]))

(def example-buffer-window
  {:id 1
   :type :buffer
   :blocks [
            [{:style "normal" :text "This is "}
             {:style "emphatic" :text "an "}
             {:style "normal" :text "example"}
             {:style "emphatic" :text "of game state."}]

            [{:style "normal" :text "With two paras."}]
                       
            ]})

(def example-buffer-update
  {:id 1
   :clear false
   :text [{:append true :content ["normal" "And this is appended."]}
          {:content ["normal" "But this will form a new line."]}]})

(def example-buffer-window-updated
  {:id 1
   :type :buffer
   :blocks [
            [{:style "normal" :text "This is "}
             {:style "emphatic" :text "an "}
             {:style "normal" :text "example"}
             {:style "emphatic" :text "of game state."}]

            [{:style "normal" :text "With two paras."}
             {:style "normal" :text "And this is appended."}]

            [{:style "normal" :text "But this will form a new line."}] ]})

(def example-grid-window
  {:id 2
   :type :grid
   :blocks [
            [{:style "normal" :text "This is a "}
             {:style "emphatic" :text "grid"}
             {:style "normal" :text "window line."}]

            [{:style "normal" :text "And this is the second line."}]

            ]})

(def example-grid-update
  {:id 2
   :lines [{:line 0 :content [{:style "normal" :text "Grid text" :hyperlink true}]}
           {:line 1 :content ["emphatic" "and some more for the second line."]}]})

(def example-grid-window-updated
  {:id 2
   :type :grid
   :blocks [[{:style "normal" :text "Grid text" :hyperlink true}]
            [{:style "emphatic" :text "and some more for the second line."}]]})

(def example-game-state
  {:windows [example-buffer-window
             example-grid-window]})

(def example-content-update-array
  [example-buffer-update example-grid-update])

(deftest normalise-lda
  (is (= [{:style "style1" :text "string1"}
          {:style "style2" :text "string2"}
          {:style "style3" :text "string3"}
          {:style "style4" :text "string4"}]
         (omkote/normalise-line-data-array ["style1" "string1"
                                            "style2" "string2"
                                            {:style "style3" :text "string3"}
                                            "style4" "string4"])))
  
  (let [normalised [{:style "x" :text "y" :hyperlink true}]]
    (is (= (omkote/normalise-line-data-array normalised) normalised))))

(deftest append-content-paragraphs
  (let [existing [[{:style "style1" :text "string1"}
                   {:style "style2" :text "string2"}]
                  [{:style "style3" :text "string3"}
                   {:style "style4" :text "string4"}]]
        updates [{:append true :content (omkote/normalise-line-data-array
                                         ["style5" "string5" "style6" "string6"])}
                 {:append false :content [{:style "style7" :text "string7"}]}]]
    
    (is (= [[{:style "style1" :text "string1"}
             {:style "style2" :text "string2"}]
            [{:style "style3" :text "string3"}
             {:style "style4" :text "string4"}
             {:style "style5" :text "string5"}
             {:style "style6" :text "string6"}]
            [{:style "style7" :text "string7"}]]

           (omkote/append-content-paragraphs existing updates)))

    (is (= (omkote/append-content-paragraphs []
                                         [{:content [{:style "normal" :text "Some text."}] :append false}
                                          {:content (omkote/normalise-line-data-array [])}
                                          {:content [{:style "normal" :text "And more."}]}
                                          {:content [{:style "normal" :text "Welcome to..."}] :append false}
                                          {:content [{:style "header" :text "The Game"}] :append true}])
           [[{:style "normal" :text "Some text."}]
            [{:style "" :text ""}]
            [{:style "normal" :text "And more."}]
            [{:style "normal" :text "Welcome to..."} {:style "header" :text "The Game"}]]))))

(deftest test-update-window-content
  (is (= (omkote/update-window-content example-buffer-window example-buffer-update)
         example-buffer-window-updated))
  (is (= (omkote/update-window-content example-grid-window example-grid-update)
         example-grid-window-updated)))


(deftest test-update-window-contents
  (is (= (omkote/update-windows [example-buffer-update] omkote/update-window-content [example-buffer-window])
         [example-buffer-window-updated]))
  (is (= (omkote/update-windows [example-grid-update] omkote/update-window-content [example-grid-window])
         [example-grid-window-updated])))


(deftest test-apply-update
  (is (= (omkote/apply-update-message {:windows [example-buffer-window example-grid-window]}
                                      {:type :update
                                       :content [example-grid-update example-buffer-update]})
         {:windows [example-buffer-window-updated example-grid-window-updated]})))

;; this is going into a web page for testing so have them run automatically
(run-tests 'omkote.tests)

