(ns metabase.feature-extraction.math
  "Math functions and utilities."
  (:require [kixi.stats
             [core :as stats]
             [math :as math]]
            [redux.core :as redux]))

(defn safe-divide
  "Like `clojure.core//`, but returns nil if denominator is 0."
  [x & denominators]
  (when (or (and (not-empty denominators) (not-any? zero? denominators))
            (and (not (zero? x)) (empty? denominators)))
    (apply / x denominators)))

(defn growth
  "Relative difference between `x1` an `x2`."
  [x2 x1]
  (when (and x1 x2 (not (zero? x1)))
    (let [x2 (double x2)
          x1 (double x1)]
      (cond
        (every? neg? [x1 x2])     (growth (- x1) (- x2))
        (and (neg? x1) (pos? x2)) (- (growth x1 x2))
        (neg? x1)                 (- (growth x2 x1))
        :else                     (/ (- x2 x1) x1)))))

(defn saddles
  "Returns the number of saddles in a given series."
  [series]
  (->> series
       (partition 2 1)
       (map (fn [[[_ y1] [x y2]]]
              [x (- y2 y1)]))
       (partition-by (comp pos? second))
       count
       dec))

(defn roughly=
  "Is `x` èqual to `y` within precision `precision` (default 0.05)."
  ([x y] (roughly= x y 0.05))
  ([x y precision]
   (<= (* (- 1 precision) x) y (* (+ 1 precision) x))))

(defn autocorrelation
  "Calculate autocorrelation at lag `lag` or find the lag with the highest
   autocorrelation if `lag` is not given.
   https://en.wikipedia.org/wiki/Autocorrelation"
  ([xs]
   (reduce (fn [best lag]
             (let [r (autocorrelation lag xs)]
               (if (pos? (compare
                          [(math/abs r) (- lag)]
                          [(math/abs (:autocorrelation best)) (- (:lag best))]))
                 {:autocorrelation r
                  :lag             lag}
                 best)))
           {:lag             0
            :autocorrelation 0}
           (range 1 (/ (count xs) 2))))
  ([lag xs]
   (transduce identity (stats/correlation first second)
              (map vector xs (drop lag xs)))))

(def linear-regression
  "Transducer that calculats (simple) linear regression."
  (redux/post-complete (stats/simple-linear-regression first second)
                       (partial zipmap [:offset :slope])))

(defn variation-trend
  "Calculate the trend of variation using a sliding window of width `period`.
   Assumes `xs` are evenly (unit) spaced.
   https://en.wikipedia.org/wiki/Variance"
  [period xs]
  (transduce (map-indexed (fn [i xsi]
                            [i (transduce identity
                                          (redux/post-complete
                                           (redux/fuse {:var  stats/variance
                                                        :mean stats/mean})
                                           (fn [{:keys [var mean]}]
                                             (/ var mean)))
                                          xsi)]))
             (redux/post-complete
              (redux/fuse {:slope (redux/post-complete linear-regression :slope)})
              :slope)
             (partition period 1 xs)))

(def magnitude
  "Transducer that claclulates magnitude (Euclidean norm) of given vector.
   https://en.wikipedia.org/wiki/Euclidean_distance"
  (redux/post-complete (redux/pre-step + math/sq) math/sqrt))

(defn cosine-distance
  "Cosine distance between vectors `a` and `b`.
   https://en.wikipedia.org/wiki/Cosine_similarity"
  [a b]
  (transduce identity
             (redux/post-complete
              (redux/fuse {:magnitude-a (redux/pre-step magnitude first)
                           :magnitude-b (redux/pre-step magnitude second)
                           :product     (redux/pre-step + (partial apply *))})
              (fn [{:keys [magnitude-a magnitude-b product]}]
                (some->> (safe-divide product magnitude-a magnitude-b) (- 1))))
             (map (comp (partial map double) vector) a b)))

(defn head-tails-breaks
  "Pick out the cluster of N largest elements.
   https://en.wikipedia.org/wiki/Head/tail_Breaks"
  ([keyfn xs] (head-tails-breaks 0.6 keyfn xs))
  ([threshold keyfn xs]
   (let [mean (transduce (map keyfn) stats/mean xs)
         head (filter (comp (partial < mean) keyfn) xs)]
     (cond
       (empty? head)                 xs
       (>= threshold (/ (count head)
                        (count xs))) (recur threshold keyfn head)
       :else                         head))))

(defn chi-squared-distance
  "Chi-squared distane between empirical probability distributions `p` and `q`.
   http://www.aip.de/groups/soe/local/numres/bookcpdf/c14-3.pdf"
  [p q]
  (/ (reduce + (map (fn [pi qi]
                      (cond
                        (zero? pi) qi
                        (zero? qi) pi
                        :else      (/ (math/sq (- pi qi))
                                      (+ pi qi))))
                    p q))
     2))

(def ^:private ^{:arglists '([pdf])} pdf->cdf
  (partial reductions +))

(defn ks-test
  "Perform the Kolmogorov-Smirnov test.
   Takes two samples parametrized by size (`m`, `n`) and distribution (`p`, `q`)
   and returns true if the samples are statistically significantly different.
   Optionally takes an additional `significance-level` parameter.
   https://en.wikipedia.org/wiki/Kolmogorov%E2%80%93Smirnov_test"
  ([m p n q] (ks-test 0.95 m p n q))
  ([significance-level m p n q]
   (when-not (zero? (* m n))
     (let [D (apply max (map (comp math/abs -) (pdf->cdf p) (pdf->cdf q)))
           c (math/sqrt (* -0.5 (Math/log (/ significance-level 2))))]
       (> D (* c (math/sqrt (/ (+ m n) (* m n)))))))))
