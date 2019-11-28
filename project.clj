(defproject clj-http "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [io.netty/netty-all "4.1.43.Final"]
                 [clj-time "0.15.2"]
                 [com.novemberain/pantomime "2.11.0"]]
  :main ^:skip-aot clj-http.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
