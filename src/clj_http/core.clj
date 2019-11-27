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
    [java.nio.charset Charset])
  (:require [clojure.string :refer [trim join]]
            [clj-http.helper-macros :refer [cond-let]]))

(def http-methods #{"GET" "POST" "HEAD" "OPTIONS" "PUT" "DELETE" "TRACE" "CONNECT"})

(defn throw-error [s] (println s))

(defn read-method [s mthds]
  (if-let [method (re-find #"^\S+" s)]
    (if (contains? mthds method)
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

(defn handle-post [request-map])
(defn handle-head [request-map])
(defn handle-options [request-map])
(defn handle-put [request-map])
(defn handle-delete [request-map])
(defn handle-trace [request-map])
(defn handle-connect [request-map])

(defn handle-get [request-map]
  (let [raw-path (-> request-map :request-uri .path)
        abs-path (if (= "/" raw-path)
                   (str "server-files/" "index.html")
                   (str "server-files/" raw-path))]
    (slurp abs-path)))

(defn encode-http-request [response-map ctx msg]
  (let [bytes (.. Unpooled (copiedBuffer response-map (Charset/forName "UTF-8")))]
    (.writeAndFlush ctx bytes)))

(defn process-http-request [request-map]
  (if-let [handle-method ({"GET" handle-get
                           "POST" handle-post
                           "HEAD" handle-head
                           "OPTIONS" handle-options
                           "PUT" handle-put
                           "DELETE" handle-delete
                           "TRACE" handle-trace
                           "CONNECT" handle-connect}
                          (request-map :request-method))]
    (handle-method request-map)))

(defn decode-http-request [msg]
  (let [http-str (.toString msg (Charset/forName "UTF-8"))
        [method rmn] (read-method http-str http-methods)
        [uri rmn] (read-uri rmn)
        [version rmn] (read-version rmn)
        [headers rmn] (read-headers rmn)
        body (if-let [length (headers "content-length")]
               (read-body rmn (Integer/parseInt length))
               nil)]
    (assoc {}
      :request-method method
      :request-uri uri
      :protocol-version version
      :headers headers
      :request-body body)))

(defn handle-http-request []
  (proxy [ChannelInboundHandlerAdapter] []
    (channelRead [ctx msg]
      (-> msg
          decode-http-request
          process-http-request
          (encode-http-request ctx msg)))
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