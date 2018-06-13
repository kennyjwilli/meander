(ns meander.dev.search
  (:refer-clojure :exclude [compile])
  (:require [clojure.spec.alpha :as s]
            [meander.dev.match :as r.match]
            [meander.dev.syntax :as r.syntax]
            [meander.util :as r.util]))

(declare compile)

;; ---------------------------------------------------------------------
;; Matrix

(defn search-and-match-matrices
  "Splits matrix into search and match matrices and stores them in a
  map under the keys :search and :match respectively.

  A search matrix is a pattern matrix such that every pattern in the
  first column is a search pattern (node). A match matrix is pattern
  matrix such that no pattern in the first column is a search
  pattern."
  [matrix]
  (group-by
   (fn [row]
     (if (r.syntax/search? (r.match/first-column row))
       :search
       :match))
   matrix))


(defn compile-ctor-clauses-strategy [tag _vars _rows _default]
  tag)


(defmulti compile-ctor-clauses
  #'compile-ctor-clauses-strategy)


(defn concat-form
  {:private true}
  [forms]
  (if (< 1 (count forms))
    `(concat ~@forms)
    (first forms)))


(defn compile-match-matrix
  "Compiles each first column of each row in match-matrix as an
  idependent singleton match matrix with the match compiler. The
  right hand side of each row is rewritten as the compilation of
  it's remaining columns wit the search compiler."
  {:arglists '([vars match-matrix default])
   :private true}
  [vars matrix default]
  (concat-form
   (mapv r.match/compile
         (repeat (take 1 vars))
         (mapv
          (fn [row]
            (vector
             (assoc row
                    :cols [(r.match/first-column row)]
                    :rhs
                    (let [cols* (r.match/rest-columns row)]
                      (if (seq cols*)
                        (compile
                         (drop 1 vars)
                         [(assoc row :cols (r.match/rest-columns row))]
                         default)
                        `(list ~(:rhs row)))))))
          matrix)
         (repeat default))))


(defmethod compile-ctor-clauses :default [_ vars rows default]
  (compile-match-matrix vars rows default))


;; ---------------------------------------------------------------------
;; Seq, Vector


(defn compile-sequential-matrix
  {:private true}
  [vars matrix default]
  (compile vars
           (map
            (fn [row]
              (assoc row
                     :cols (cons (r.syntax/data (r.match/first-column row))
                                 (r.match/rest-columns row))))
            matrix)
           default))


(defmethod compile-ctor-clauses :seq [_ vars search-matrix default]
  `(if (seq? ~(first vars))
     ~(compile-sequential-matrix vars search-matrix default)))


(defmethod compile-ctor-clauses :vec [_ vars search-matrix default]
  `(if (vector? ~(first vars))
     ~(compile-sequential-matrix vars search-matrix default)))


;; ---------------------------------------------------------------------
;; Part, VPart


(defn compile-part-matrix
  {:private true}
  [vars search-matrix default]
  (let [left-sym (gensym "left__")
        right-sym (gensym "right__")
        vars* (concat [left-sym right-sym] (rest vars))
        {:keys [variable-length invariable-length]}
        (group-by
         (comp {true :variable-length
                false :invariable-length}
               r.syntax/variable-length?
               r.syntax/left-node
               r.match/first-column)
         search-matrix)
        forms (mapv
               (fn [[n rows]]
                 `(let [~left-sym (take ~n ~(first vars))
                        ~right-sym (drop ~n ~(first vars))]
                    ~(compile vars*
                              (map
                               (fn [row]
                                 (let [{:keys [left right]} (r.syntax/data (r.match/first-column row))]
                                   (assoc (r.match/drop-column row)
                                          :cols (concat [left right] (r.match/rest-columns row)))))
                               invariable-length)
                              default)))
               (group-by
                (comp r.syntax/length
                      r.syntax/left-node
                      r.match/first-column)
                invariable-length))
        forms (if (seq variable-length)
                (conj forms
                      `(sequence
                        (mapcat
                         (fn [[~left-sym ~right-sym]]
                           ~(compile vars*
                                     (map
                                      (fn [row]
                                        (let [{:keys [left right]} (r.syntax/data (r.match/first-column row))]
                                          (assoc (r.match/drop-column row)
                                                 :cols (concat [left right] (r.match/rest-columns row)))))
                                      variable-length)
                                     default)))
                        (r.util/partitions 2 ~(first vars))))
                forms)]
    (concat-form forms)))


(defmethod compile-ctor-clauses :part [_ vars rows default]
  (compile-part-matrix vars rows default))


(defmethod compile-ctor-clauses :vpart [_ vars rows default]
  (compile-part-matrix vars rows default))


;; ---------------------------------------------------------------------
;; Cat, VCat


(defn compile-cat-clauses [tag vars rows default]
  (let [forms (mapv
               (fn [[n rows]]
                 (let [target (first vars)
                       nth-forms (map
                                  (fn [index]
                                    [(gensym (str "nth_" index "__"))
                                     `(nth ~target ~index)])
                                  (range n))
                       nth-vars (map first nth-forms)
                       vars* (concat nth-vars (rest vars))
                       rows* (map
                              (fn [row]
                                (assoc row
                                       :cols (concat
                                              (r.syntax/data (r.match/first-column row))
                                              (r.match/rest-columns row))))
                              rows)]
                   (case tag
                     :cat
                     `(if (== ~n (count (take ~n ~target)))
                        (let [~@(mapcat identity nth-forms)]
                          ~(compile vars* rows* default)))

                     :vcat
                     `(if (== ~n (count ~target))
                        (let [~@(mapcat identity nth-forms)]
                          ~(compile vars* rows* default))))))
               (group-by
                (comp r.syntax/cat-length r.match/first-column)
                rows))]
    (concat-form forms)))


(defmethod compile-ctor-clauses :cat [tag vars rows default]
  (compile-cat-clauses tag vars rows default))


(defmethod compile-ctor-clauses :vcat [tag vars rows default]
  (compile-cat-clauses tag vars rows default))


(defn compile
  {:private true}
  [vars matrix default]
  (if (some? (r.match/first-column (first matrix)))
    (let [matrices (search-and-match-matrices matrix)
          search-matrix (:search matrices)
          match-matrix (:match matrices)]
      (concat-form
       (cond-> []
         search-matrix
         (into (mapv
                (fn [[tag rows]]
                  (compile-ctor-clauses tag vars rows default))
                (group-by
                 (comp r.syntax/tag r.match/first-column)
                 search-matrix)))

         match-matrix
         (conj (compile-match-matrix vars match-matrix default)))))
    default))


(s/fdef search
  :args ::r.match/match-args
  :ret any?)


(defn parse-search-args
  {:private true}
  [match-args]
  (s/conform ::r.match/match-args match-args))


(defmacro search
  {:arglists '([target & pattern action ...])
   :style/indent :defn}
  [& search-args]
  (let [{:keys [target clauses]} (parse-search-args search-args)
        final-clause (some
                      (fn [{:keys [pat] :as clause}]
                        (when (= pat '[:any _])
                          clause))
                      clauses)
        clauses* (if final-clause
                   (remove (comp #{[:any '_]} :pat) clauses)
                   clauses)
        target-sym (gensym "target__")
        vars [target-sym]
        rows (sequence
              (map
               (fn [{:keys [pat rhs]}]
                 {:cols [pat]
                  :env #{}
                  :rhs rhs}))
              clauses*)]
    `(let [~target-sym ~target]
       ~(compile vars rows (if final-clause
                             `(list ~(:rhs final-clause)))))))





(comment
  (defn example [x]
    (search x 
      [!ws ... . [!xs ... . !ys ...] . !zs ...]
      {:!ws !ws
       :!xs !xs
       :!ys !ys
       :!zs !zs}

      [!xs ... . [?a ... . ?b ...] . !ys ...]
      {:?a ?a
       :?b ?b}

      [:A :B . !xs ... . :C :D]
      {:!xs !xs}))
  (time
    (example [:A :B [1 1 1 2 2] :C :D]))
  ;; =>
  "Elapsed time: 0.226812 msecs"
  ({:!ws [:A :B], :!xs [], :!ys [1 1 1 2 2], :!zs [:C :D]}
   {:!ws [:A :B], :!xs [1], :!ys [1 1 2 2], :!zs [:C :D]}
   {:!ws [:A :B], :!xs [1 1], :!ys [1 2 2], :!zs [:C :D]}
   {:!ws [:A :B], :!xs [1 1 1], :!ys [2 2], :!zs [:C :D]}
   {:!ws [:A :B], :!xs [1 1 1 2], :!ys [2], :!zs [:C :D]}
   {:!ws [:A :B], :!xs [1 1 1 2 2], :!ys [], :!zs [:C :D]}
   {:?a 1, :?b 2}
   {:!xs [[1 1 1 2 2]]}))