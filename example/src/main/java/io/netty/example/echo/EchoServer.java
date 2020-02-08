/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.example.echo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;

/**
 * Echoes back any received data from a client.
 */
public final class EchoServer {

    static final boolean SSL = System.getProperty("ssl") != null;
    static final int PORT = Integer.parseInt(System.getProperty("port", "8007"));

    public static void main(String[] args) throws Exception {
        // 配置SSL
        final SslContext sslCtx;
        if (SSL) {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
        } else {
            sslCtx = null;
        }

        /**
         * 1. bossGroup, workerGroup 这俩 EventLoopGroup 对象是整个Netty核心对象，
         * 其分别对应Reactor模型中的 mainReactor 和 subReactor 模型。
         * - bossGroup 用于接收 tcp 请求处理 acccept 事件，然后他将请求交给 workerGroup。
         * - workerGroup 会获取到真正的连接，然后与连接通信，进行 read -> decode -> do somthing -> encode -> write 等操作.
         *
         * 2. EventLoopGroup 是事件循环组（线程组），其含有多个 EventLoop ，可以注册 channel ,用于事件循环中进行选择器的选择事件。
         *
         * 3. new NioEventLoopGroup(1); 指定了使用1个线程，如果使用默认构造器，则默认是使用 cpus * 2 个.
         * 在其父类 MultithreadEventLoopGroup 中，
         * DEFAULT_EVENT_LOOP_THREADS = Math.max(1, SystemPropertyUtil.getInt(
                    "io.netty.eventLoopThreads", NettyRuntime.availableProcessors() * 2));
         *
         * 4. 父类 MultithreadEventLoopGroup 构造器中回创建 大小为所配置的线程数的 EventExecutor 数组
         * children = new EventExecutor[nThreads];
         * children[i] = newChild(executor, args); // newChild 由子类实现。
         * 同时，也会对每个 EventExecutor 做监听：
         * for (EventExecutor e: children) {
             e.terminationFuture().addListener(terminationListener);
            }
         *
         * 5. 创建服务端引导类 ServerBootstrap ，用于启动服务器和引导整个程序初始化。它和 ServerChannel 关联，
         * ServerChannel 是 Channel 的子类。
         *
         * 6. group(EventLoopGroup parentGroup, EventLoopGroup childGroup) 是配置 bossGroup 和 workerGroup
         * 到父类的 group 和 自己的childGroup 字段中，用于后期引导使用。
         *
         * 7. channel(Class<? extends C> channelClass) 是通过 这个 Class 对象反射创建 channelFactory。
         * 调用处是在 AbstractBootstrap.initAndRegister() 中 ，
         * channel = channelFactory.newChannel(); // channelFactory 的实现类是 ReflectiveChannelFactory
         *
         * 8. option(ChannelOption<T> option, T value) 用来配置一些 tcp 相关的选项，并放在一个 LinkedHashMap 中
         *
         * 9. handler(new LoggingHandler(LogLevel.INFO))添加一个服务器专属的日志处理器类， handler方法的 handler 是给
         * bossGroup 用的
         *
         * 10. childHandler(ChannelHandler childHandler), 是给 workerGroup 用的，其添加了一个 SocketChannel (而不是 Server端的
         * ServerSocketChannel) 的 handler。
         *
         * 11. 绑定端口，阻塞至连接成功。
         *
         * 12. main 线程阻塞等待你关闭。
         *
         * 13. 优雅的关闭 bossGroup 、 workerGroup 中的所有资源。
         */
        // 1、 2、 3、4
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        final EchoServerHandler serverHandler = new EchoServerHandler();
        try {
            ServerBootstrap b = new ServerBootstrap(); // 5
            b.group(bossGroup, workerGroup) // 6
                    .channel(NioServerSocketChannel.class) // 7
                    .option(ChannelOption.SO_BACKLOG, 100) // 8
                    .handler(new LoggingHandler(LogLevel.INFO)) // 9
                    .childHandler(new ChannelInitializer<SocketChannel>() { // 10
                        @Override
                        public void initChannel(SocketChannel ch) throws Exception {
                            ChannelPipeline p = ch.pipeline();
                            if (sslCtx != null) {
                                p.addLast(sslCtx.newHandler(ch.alloc()));
                            }
                            //p.addLast(new LoggingHandler(LogLevel.INFO));
                            p.addLast(serverHandler);
                        }
                    });

            // Start the server.
            ChannelFuture f = b.bind(PORT).sync(); // 11

            // Wait until the server socket is closed.
            f.channel().closeFuture().sync(); // 12
        } finally {
            // Shut down all event loops to terminate all threads.
            // 13
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
