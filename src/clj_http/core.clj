(ns clj-http.core
  (:import
    [io.netty.buffer ByteBuf]
    [io.netty.channel
     ChannelHandlerContext ChannelInboundHandlerAdapter ChannelFuture
     ChannelInitializer ChannelHandler ChannelOption EventLoopGroup]
    [io.netty.channel.nio NioEventLoopGroup]
    [io.netty.channel.socket SocketChannel]
    [io.netty.channel.socket.nio NioServerSocketChannel]
    [io.netty.bootstrap ServerBootstrap]
    [java.nio.charset Charset])
  (:require [clojure.string :refer [trim join]]
            [clj-http.helper-macros :refer [cond-let]]))


(def http-methods #{"GET" "POST" "HEAD" "OPTIONS" "PUT" "DELETE" "TRACE" "CONNECT"})

(defn throw-error [s] (println s))

(defn method-parser [s mthds]
  (if-let [method (re-find #"^\S+" s)]
    (if (contains? mthds method)
      [method (subs s (count method))]
      (throw-error "Invalid Method"))
    (throw-error "Invalid HTTP Request")))

(defn uri-parser [s]
  (if-let [uri (get (re-find #"^ (\S+)" s) 1)]
    [uri (subs s (inc (count uri)))]
    (throw-error "Invalid route field")))

(defn version-parser [s]
  (if-let [uri (get (re-find #"^ (\S+)\r\n" s) 1)]
    [uri (subs s (+ 3 (count uri)))]
    (throw-error "Invalid version field")))

(defn header-parser [s]
  (loop [rmn s, key nil, val nil, key? true, res {}]
    (println rmn)
    (cond-let
      (re-find #"^\r\n\r\n" rmn) [(assoc res key val) (subs rmn 4)]
      (re-find #"^\r\n" rmn) (recur (subs rmn 2) nil nil true (assoc res key val))
      [colon (re-find #"^\: (?! +)" rmn)] (recur (subs rmn (count colon)) key val false res)
      [match (re-find #"(?![\(\)\,\/\:\;<=>\?@\[\]\\\{\}\"])[\x21-\x7E]+" rmn)]
      (if key?
        (recur (subs rmn (count match)) match val key? res)
        (recur (subs rmn (count match)) key match key? res))
      :else (throw-error "Invalid HTML message"))))

(defn body-parser [s len]
  (if (= (count s) len)
    s
    (throw-error "Invalid Msg body")))

(defn create-req-map [s mthds]
  (let [[method rmn] (method-parser s mthds), [uri rmn] (uri-parser rmn), [version rmn] (version-parser rmn)
        [headers rmn] (header-parser rmn)]
    (println method uri version headers)
    (assoc {}
      :request-method method
      :request-uri uri
      :protocol-version version
      :headers headers
      :request-body (body-parser rmn (headers :Content-Length)))))

(defn parse-http-req [msg]
  (let [http-string (.toString msg (Charset/forName "UTF-8"))]
    (def hs http-string)))

(defn http-req-handler []
  (proxy [ChannelInboundHandlerAdapter] []
    (channelRead [ctx msg]
      (do (parse-http-req msg)))
    (exceptionCaught [ctx cause]
      (do (.printStackTrace cause)
          (.close ctx)))))

(defn server-bootstrap [boss-group worker-group handler]
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
      (let [bootstrap (server-bootstrap boss-group worker-group handler)
            channel (.. bootstrap (bind port) sync (channel))]
        (-> channel
            .closeFuture
            .sync)
        channel)
      (finally (do (.shutdownGracefully boss-group)
                   (.shutdownGracefully worker-group))))))


;; (re-find #"(?![\(\)\,\/\:\;<=>\?@\[\]\\\{\}\"])[\x21-\x7E]+" "(),/:;<=>?@[]{}")


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