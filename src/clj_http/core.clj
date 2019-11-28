(ns clj-http.core
  (:import
    [io.netty.channel
     ChannelHandlerContext ChannelInboundHandlerAdapter ChannelFuture
     ChannelInitializer ChannelHandler ChannelOption EventLoopGroup]
    [io.netty.channel.nio NioEventLoopGroup]
    [io.netty.buffer Unpooled]
    [io.netty.channel.socket SocketChannel]
    [io.netty.channel.socket.nio NioServerSocketChannel]
    [io.netty.bootstrap ServerBootstrap]
    [io.netty.handler.codec.http QueryStringDecoder]
    [java.nio.charset Charset]
    [java.time.format DateTimeFormatter]
    [java.time ZonedDateTime ZoneOffset]
    [java.io File ByteArrayOutputStream]
    [java.nio.file Files]
    [com.google.common.primitives Bytes])
  (:require [clojure.string :refer [trim join]]
            [clj-http.helper-macros :refer [cond-let]]
            [clj-time.format :as f]
            [clj-time.core :as t]
            [pantomime.mime :refer [mime-type-of]]))
(def ^:private time-format (f/formatter "EEE, dd MMM yyyy HH:mm:ss"))
(defn- time->str [time] (str (f/unparse time-format time) " GMT"))

(defn deep-merge [a b]
  (merge-with (fn [x y]
                (cond (map? y) (deep-merge x y)
                      (vector? y) (concat x y)
                      :else y))
              a b))

(defn handle-post [request-map])
(defn handle-head [request-map])
(defn handle-options [request-map])
(defn handle-put [request-map])
(defn handle-delete [request-map])
(defn handle-trace [request-map])
(defn handle-connect [request-map])



(defn handle-get [request-map]
  (try (let [raw-path (-> request-map :request-uri .path)
             abs-path (if (= "/" raw-path) (str "server-files/" "index.html") (str "server-files/" raw-path))
             resource (.. Files (readAllBytes (.toPath (File. abs-path))))]
         {:body resource
          :headers {"Content-Type" (mime-type-of abs-path)
                    "Content-Length" (alength resource)}})
       (catch Exception e {:status-code 404
                           :reason-phrase "Not Found"})))

(def http-methods {"GET" #(handle-get %) "POST" #(handle-post %) "HEAD" #(handle-head %)
                   "OPTIONS" #(handle-options %) "PUT" #(handle-put %) "DELETE" #(handle-delete %)
                   "TRACE" #(handle-trace %) "CONNECT" #(handle-connect %)})

(defn throw-error [s] (println s))

(defn read-method [s]
  (if-let [method (re-find #"^\S+" s)]
    (if (contains? (set (keys http-methods)) method)
      [method (subs s (count method))]
      (throw-error "Invalid Method"))
    (throw-error "Invalid HTTP Request")))

(defn read-uri [s]
  (if-let [uri (get (re-find #"^ (\S+)" s) 1)]
    [(QueryStringDecoder. uri) (subs s (inc (count uri)))]
    (throw-error "Invalid route field")))

(defn read-version [s]
  (if-let [uri (get (re-find #"^ (\S+)\r\n" s) 1)]
    [uri (subs s (+ 3 (count uri)))]
    (throw-error "Invalid version field")))

(defn read-headers [s]
  (loop [rmn s, key nil, val nil, key? true, res {}]
    (cond-let
      (re-find #"^\r\n\r\n" rmn) [(assoc res key val) (subs rmn 4)]
      (re-find #"^\r\n" rmn) (recur (subs rmn 2) nil nil true (assoc res key val))
      [colon (re-find #"^\: (?! +)" rmn)] (recur (subs rmn (count colon)) key val false res)
      [match (re-find #"^(?:(?![\:])[\x21-\x7E ])+" rmn)]
      (if key?
        (recur (subs rmn (count match)) (.toLowerCase match) val key? res)
        (recur (subs rmn (count match)) key match key? res))
      :else (throw-error "Invalid HTML message"))))

(defn read-body [s content-length]
  (if (= (count s) content-length) s
    (throw-error "Invalid Msg body")))

(defn encode-http-request [response-map ctx]
  (let [status-line (str (response-map :protocol-version) " " (response-map :status-code) " " (response-map :reason-phrase) "\r\n")
        headers-appended (str (reduce-kv (fn [s k v] (str s k ": " v "\r\n")) status-line (response-map :headers)) "\r\n")
        s-h-bytes (.getBytes headers-appended (Charset/forName "UTF-8"))
        concated-bytes (byte-array (mapcat seq [s-h-bytes (response-map :body)]))
        bytes->byte-buf (.. Unpooled (wrappedBuffer concated-bytes))]
    (.writeAndFlush ctx bytes->byte-buf)))

(defn process-http-request [request-map]
  (let [method (request-map :request-method), handler (http-methods method)]
    (deep-merge {:protocol-version "HTTP/1.1"
                 :status-code 200
                 :reason-phrase "OK"
                 :headers {"Server" "Clj-HTTP 0.1", "Date" (time->str (t/now))}}
           (handler request-map))))

(defn decode-http-request [msg]
  (let [http-str (.toString msg (Charset/forName "UTF-8")), [method rmn] (read-method http-str),
        [uri rmn] (read-uri rmn), [version rmn] (read-version rmn), [headers rmn] (read-headers rmn),
        body (if-let [length (headers "content-length")] (read-body rmn (Integer/parseInt length)) nil)]
    (assoc {} :request-method method :request-uri uri :protocol-version version :headers headers :body body)))

(defn handle-http-request []
  (proxy [ChannelInboundHandlerAdapter] []
    (channelRead [ctx msg]
      (-> msg decode-http-request process-http-request (encode-http-request ctx)))
    (exceptionCaught [ctx cause]
      (do (.printStackTrace cause)
          (.close ctx)))))

(defn bootstrap-server [boss-group worker-group handler]
  (.. (ServerBootstrap.)
      (group boss-group worker-group)
      (channel NioServerSocketChannel)
      (childHandler
        (proxy [ChannelInitializer] []
          (initChannel [channel]
            (.. channel
                (pipeline)
                (addLast (into-array ChannelHandler [(handler)]))))))
      (option ChannelOption/SO_BACKLOG (int 128))
      (childOption ChannelOption/SO_KEEPALIVE true)))

(defn start-server [handler port]
  (let [boss-group (NioEventLoopGroup.) worker-group (NioEventLoopGroup.)]
    (try
      (let [bootstrap (bootstrap-server boss-group worker-group handler)
            channel (.. bootstrap (bind port) sync (channel))]
        (-> channel .closeFuture .sync) channel)
      (finally (do (.shutdownGracefully boss-group) (.shutdownGracefully worker-group))))))




;; (re-find #"(?![\(\)\,\/\:\;<=>\?@\[\]\\\{\}\"])[\x21-\x7E]+" "(),/:;<=>?@[]{}")

;(re-find #"^(?:(?![\(\)\,\/\:\;<=>\?@\[\]\\\{\}\"])[\x21-\x7E])+" rmn)

;"Content-Type: text/plain\r
;User-Agent: PostmanRuntime/7.20.1\r
;Accept: */*\r
;Cache-Control: no-cache\r
;Postman-Token: f31ca1cd-6c37-4e77-8fc0-0d9216a623ae\r
;Host: localhost\r
;Accept-Encoding: gzip, deflate\r
;Content-Length: 3\r
;Connection: keep-alive\r
;\r
;h
;c"

;"GET / HTTP/1.1\r\nContent-Type: text/plain\r\nUser-Agent: PostmanRuntime/7.20.1\r\nAccept: */*\r\nCache-Control: no-cache\r\nPostman-Token: ee7685c1-8b93-4cae-b381-c375fc24eca3\r\nHost: localhost\r\nAccept-Encoding: gzip, deflate\r\nContent-Length: 3\r\nConnection: keep-alive\r\n\r\nh\nc"


;Host: localhost
;Connection: keep-alive
;Pragma: no-cache
;Cache-Control: no-cache
;Upgrade-Insecure-Requests: 1
;User-Agent: Mozilla/5.0 (Macintosh); Intel Mac OS X 10_15_1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/78.0.3904.108 Safari/537.36

;DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss O");
;System.out.println(formatter.format(ZonedDateTime.now(ZoneOffset.UTC)));
;
;(.ofPattern DateTimeFormatter "EEE, dd MMM yyyy HH:mm:ss O")

 ;(.. ZonedDateTime (now (.UTC ZoneOffset))))

;(.. DateTimeFormatter (ofPattern "EEE, dd MMM yyyy HH:mm:ss O") (format (.. ZoneOffset (of "+h"))))

;(defn process-http-request [request-map]
;  (condp = (request-map :request-method)
;    "GET" (handle-get request-map)
;    "POST" (handle-post request-map)
;    "HEAD" (handle-head request-map)
;    "OPTIONS" (handle-options request-map)
;    "PUT" (handle-put request-map)
;    "DELETE" (handle-delete request-map)
;    "TRACE" (handle-trace request-map)
;    "CONNECT" (handle-connect request-map)))

;byte[] bFile = Files.readAllBytes(new File(filePath).toPath());
;//or this
;byte[] bFile = Files.readAllBytes(Paths.get(filePath));


;byte a[];
;byte b[];
;
;ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
;outputStream.write( a);
;outputStream.write( b);
;
;byte c[] = outputStream.toByteArray();

;bytes (.. Unpooled (copiedBuffer body-appended (Charset/forName "UTF-8")))


;(.. (ByteArrayOutputStream.) (write s-h-bytes) (write (response-map :body)) (toByteArray))

