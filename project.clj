(defproject org.suskalo/discljord "0.1.5"
  :description "A Clojure library to allow the creation of Discord bots with a relatively high level of abstraction."
  :url "https://github.com/IGJoshua/discljord"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/core.async "0.4.474"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/tools.logging "0.4.1"]
                 [log4j/log4j "1.2.17" :exclusions [javax.mail/mail
                                                    javax.jms/jms
                                                    com.sun.jmdk/jmxtools
                                                    com.sun.jmx/jmxri]]
                 [http-kit "2.2.0"]
                 [stylefruits/gniazdo "1.0.1"]
                 [com.rpl/specter "1.1.1"]]
  :target-path "target/%s"
  :jar-name "discljord-%s.jar"
  :deploy-branches ["master" "release"]
  :profiles {:uberjar {:aot :all}
             :dev {:dependencies [[http-kit.fake "0.2.2"]
                                  [midje "1.9.2"]]}}
  :jvm-opts ["--add-modules" "java.xml.bind"
             "-Dorg.eclipse.jetty.websocket.client.LEVEL=WARN"])
