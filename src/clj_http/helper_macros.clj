(ns clj-http.helper-macros)

(defmacro cond-let
  ([] nil)
  ([exp] (throw (IllegalArgumentException. "cond-let requires an even number of forms")))
  ([cnd-bnd exp & rmn] (if (vector? cnd-bnd)
                         (let [form (cnd-bnd 0) tst (cnd-bnd 1)]
                           `(let [tmp# ~tst]
                              (if tmp#
                                (let [~form tmp#] ~exp)
                                (cond-let ~@rmn))))
                         `(if ~cnd-bnd ~exp (cond-let ~@rmn)))))
 
