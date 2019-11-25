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
   [java.nio.charset Charset]))

(defn parse-http-req [msg]
  (let [http-string (.toString msg (Charset/forName "UTF-8"))]
    (println http-string)))

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
            channel (.. bootstrap (bind port) (sync) (channel))]
        (-> channel
            .closeFuture
            .sync)
        channel)
      (finally (do (.shutdownGracefully boss-group)
                   (.shutdownGracefully worker-group))))))



