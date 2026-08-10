package com.acme.semantic.pgwire;

import com.acme.semantic.compiler.SqlCompilationException;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.execution.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import jakarta.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import org.slf4j.*;
import org.springframework.stereotype.Component;

@Component
public class PgWireServer {
  private static final Logger log = LoggerFactory.getLogger(PgWireServer.class);
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private final SemanticProperties properties;
  private final PgQueryService service;
  private EventLoopGroup boss, workers;
  private Channel channel;

  public PgWireServer(SemanticProperties p, PgQueryService s) {
    properties = p;
    service = s;
  }

  @PostConstruct
  public void start() {
    if (!properties.pgwire().enabled()) return;
    boss = new NioEventLoopGroup(1);
    workers = new NioEventLoopGroup();
    channel =
        new ServerBootstrap()
            .group(boss, workers)
            .channel(NioServerSocketChannel.class)
            .childHandler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel ch) {
                    ch.pipeline()
                        .addLast(
                            new PgDecoder(properties.pgwire().maxFrameBytes()),
                            new Handler(properties.pgwire(), service));
                  }
                })
            .bind(properties.pgwire().port())
            .syncUninterruptibly()
            .channel();
    log.info("pgwire listening on {}", properties.pgwire().port());
  }

  @PreDestroy
  public void stop() {
    if (channel != null) channel.close().syncUninterruptibly();
    if (workers != null) workers.shutdownGracefully();
    if (boss != null) boss.shutdownGracefully();
  }

  record Message(byte type, ByteBuf payload) {}

  static class PgDecoder extends ByteToMessageDecoder {
    final int maxFrameBytes;
    boolean startup = true;

    PgDecoder(int maxFrameBytes) {
      this.maxFrameBytes = maxFrameBytes;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
      if (startup) {
        if (in.readableBytes() < 4) return;
        int length = in.getInt(in.readerIndex());
        rejectOversized(length);
        if (length < 8 || in.readableBytes() < length) return;
        in.readInt();
        ByteBuf payload = in.readRetainedSlice(length - 4);
        int protocol = payload.getInt(payload.readerIndex());
        if (protocol == 80877103) {
          out.add(new Message((byte) 'S', payload));
          return;
        }
        startup = false;
        out.add(new Message((byte) 0, payload));
      } else {
        if (in.readableBytes() < 5) return;
        byte type = in.getByte(in.readerIndex());
        int length = in.getInt(in.readerIndex() + 1);
        rejectOversized(length);
        if (length < 4 || in.readableBytes() < length + 1) return;
        in.skipBytes(5);
        out.add(new Message(type, in.readRetainedSlice(length - 4)));
      }
    }

    private void rejectOversized(int length) {
      if (length > maxFrameBytes)
        throw new IllegalArgumentException(
            "PostgreSQL protocol frame exceeds " + maxFrameBytes + " bytes");
    }
  }

  static class Handler extends SimpleChannelInboundHandler<Message> {
    final SemanticProperties.Pgwire config;
    final PgQueryService service;
    boolean authenticated = false;
    String pendingPassword;
    String startupUser;
    final Map<String, PgQueryService.Prepared> statements = new HashMap<>();
    final Map<String, Portal> portals = new HashMap<>();

    Handler(SemanticProperties.Pgwire c, PgQueryService s) {
      config = c;
      service = s;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message m) {
      try {
        if (m.type == 'S'
            && m.payload.readableBytes() == 4
            && m.payload.getInt(m.payload.readerIndex()) == 80877103) {
          ctx.writeAndFlush(ctx.alloc().buffer(1).writeByte('N'));
          return;
        }
        if (m.type == 0) {
          startup(ctx, m.payload);
          return;
        }
        switch (m.type) {
          case 'p' -> password(ctx, m.payload);
          case 'Q' -> simple(ctx, cstring(m.payload));
          case 'P' -> parse(ctx, m.payload);
          case 'B' -> bind(ctx, m.payload);
          case 'D' -> describe(ctx, m.payload);
          case 'E' -> execute(ctx, m.payload);
          case 'C' -> close(ctx, m.payload);
          case 'S' -> ready(ctx);
          case 'X' -> ctx.close();
          case 'H' -> ctx.flush();
          default -> error(ctx, "0A000", "Unsupported protocol message: " + (char) m.type);
        }
      } catch (Exception e) {
        String state =
            e instanceof SqlCompilationException x
                ? x.sqlState()
                : e instanceof QueryExecutionException x ? x.sqlState() : "XX000";
        error(ctx, state, e.getMessage());
        ready(ctx);
      } finally {
        if (m.payload.refCnt() > 0) m.payload.release();
      }
    }

    void startup(ChannelHandlerContext ctx, ByteBuf b) {
      int protocol = b.readInt();
      Map<String, String> params = new HashMap<>();
      while (b.isReadable()) {
        String k = cstring(b);
        if (k.isEmpty()) break;
        params.put(k, cstring(b));
      }
      startupUser = params.get("user");
      if (!config.username().equals(startupUser)) {
        log.warn(
            "pgwire authentication rejected remote={} user={} reason=username-mismatch",
            ctx.channel().remoteAddress(),
            startupUser);
        error(ctx, "28000", "Invalid SQL username");
        ctx.close();
        return;
      }
      pendingPassword = config.password();
      message(ctx, 'R', out -> out.writeInt(3));
      ctx.flush();
    }

    void password(ChannelHandlerContext ctx, ByteBuf b) {
      String supplied = cstring(b);
      boolean matches = secureEquals(pendingPassword, supplied);
      log.info(
          "pgwire authentication remote={} user={} suppliedLength={} expectedLength={} matched={}",
          ctx.channel().remoteAddress(),
          startupUser,
          supplied.length(),
          pendingPassword == null ? 0 : pendingPassword.length(),
          matches);
      if (!matches) {
        error(ctx, "28P01", "Password authentication failed for user \"" + startupUser + "\"");
        ctx.close();
        return;
      }
      authenticated = true;
      message(ctx, 'R', out -> out.writeInt(0));
      parameter(ctx, "server_version", "14.0");
      parameter(ctx, "server_encoding", "UTF8");
      parameter(ctx, "client_encoding", "UTF8");
      parameter(ctx, "DateStyle", "ISO, MDY");
      parameter(ctx, "integer_datetimes", "on");
      message(
          ctx,
          'K',
          out -> {
            out.writeInt(SECURE_RANDOM.nextInt());
            out.writeInt(SECURE_RANDOM.nextInt());
          });
      ready(ctx);
    }

    void simple(ChannelHandlerContext ctx, String sql) {
      ensureAuth();
      PgQueryService.Prepared p = service.prepare(sql);
      sendResult(ctx, service.execute(p, List.of()), true);
      ready(ctx);
    }

    void parse(ChannelHandlerContext ctx, ByteBuf b) {
      ensureAuth();
      String name = cstring(b), sql = cstring(b);
      short count = b.readShort();
      for (int i = 0; i < count; i++) b.readInt();
      if (!statements.containsKey(name) && statements.size() >= config.maxPreparedStatements())
        throw new IllegalArgumentException("Too many prepared statements on this connection");
      statements.put(name, service.prepare(sql));
      message(ctx, '1', out -> {});
      ctx.flush();
    }

    void bind(ChannelHandlerContext ctx, ByteBuf b) {
      String portal = cstring(b), statement = cstring(b);
      PgQueryService.Prepared prepared = statements.get(statement);
      if (prepared == null) throw new IllegalArgumentException("Unknown prepared statement");
      int fmtCount = b.readUnsignedShort();
      short[] formats = new short[fmtCount];
      for (int i = 0; i < fmtCount; i++) formats[i] = b.readShort();
      int count = b.readUnsignedShort();
      List<Object> params = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        int length = b.readInt();
        if (length < 0) params.add(null);
        else {
          short format = fmtCount == 0 ? 0 : formats[Math.min(i, fmtCount - 1)];
          if (format == 0) {
            byte[] bytes = new byte[length];
            b.readBytes(bytes);
            params.add(new String(bytes, StandardCharsets.UTF_8));
          } else if (format == 1) {
            params.add(
                switch (length) {
                  case 8 -> b.readLong();
                  case 4 -> b.readInt();
                  case 2 -> b.readShort();
                  case 1 -> b.readByte() != 0;
                  default -> {
                    byte[] bytes = new byte[length];
                    b.readBytes(bytes);
                    yield bytes;
                  }
                });
          } else throw new IllegalArgumentException("Unknown parameter format: " + format);
        }
      }
      int resultFormats = b.readUnsignedShort();
      for (int i = 0; i < resultFormats; i++) b.readShort();
      if (!portals.containsKey(portal) && portals.size() >= config.maxPreparedStatements())
        throw new IllegalArgumentException("Too many portals on this connection");
      portals.put(portal, new Portal(prepared, params));
      message(ctx, '2', out -> {});
      ctx.flush();
    }

    void describe(ChannelHandlerContext ctx, ByteBuf b) {
      byte kind = b.readByte();
      String name = cstring(b);
      PgQueryService.Prepared p = kind == 'S' ? statements.get(name) : portals.get(name).prepared;
      if (kind == 'S')
        message(
            ctx,
            't',
            out -> {
              out.writeShort(p.compiled() == null ? 0 : p.compiled().parameters().size());
              if (p.compiled() != null)
                for (var ignored : p.compiled().parameters()) out.writeInt(1043);
            });
      rowDescription(ctx, p.columns());
      ctx.flush();
    }

    void execute(ChannelHandlerContext ctx, ByteBuf b) {
      String portal = cstring(b);
      b.readInt();
      Portal p = portals.get(portal);
      sendResult(ctx, service.execute(p.prepared, p.parameters), false);
    }

    void close(ChannelHandlerContext ctx, ByteBuf b) {
      byte kind = b.readByte();
      String name = cstring(b);
      if (kind == 'S') statements.remove(name);
      else portals.remove(name);
      message(ctx, '3', out -> {});
      ctx.flush();
    }

    void sendResult(ChannelHandlerContext ctx, QueryResult result, boolean describe) {
      if (!result.columns().isEmpty()) {
        if (describe) {
          List<com.acme.semantic.compiler.CompiledQuery.Column> cols =
              result.columns().stream()
                  .map(
                      c ->
                          new com.acme.semantic.compiler.CompiledQuery.Column(
                              c.name(), c.typeName()))
                  .toList();
          rowDescription(ctx, cols);
        }
        for (List<Object> row : result.rows())
          message(
              ctx,
              'D',
              out -> {
                out.writeShort(row.size());
                for (Object v : row) {
                  if (v == null) out.writeInt(-1);
                  else {
                    byte[] data = String.valueOf(v).getBytes(StandardCharsets.UTF_8);
                    out.writeInt(data.length);
                    out.writeBytes(data);
                  }
                }
              });
      }
      message(
          ctx,
          'C',
          out ->
              cstring(out, result.columns().isEmpty() ? "SET" : "SELECT " + result.rows().size()));
      ctx.flush();
    }

    void rowDescription(
        ChannelHandlerContext ctx, List<com.acme.semantic.compiler.CompiledQuery.Column> columns) {
      if (columns.isEmpty()) {
        message(ctx, 'n', out -> {});
        return;
      }
      message(
          ctx,
          'T',
          out -> {
            out.writeShort(columns.size());
            for (var c : columns) {
              cstring(out, c.name());
              out.writeInt(0);
              out.writeShort(0);
              int oid = PgTypeMapper.oid(c.semanticType());
              out.writeInt(oid);
              out.writeShort(PgTypeMapper.size(oid));
              out.writeInt(-1);
              out.writeShort(0);
            }
          });
    }

    void parameter(ChannelHandlerContext ctx, String k, String v) {
      message(
          ctx,
          'S',
          out -> {
            cstring(out, k);
            cstring(out, v);
          });
    }

    void ready(ChannelHandlerContext ctx) {
      message(ctx, 'Z', out -> out.writeByte('I'));
      ctx.flush();
    }

    void error(ChannelHandlerContext ctx, String state, String text) {
      message(
          ctx,
          'E',
          out -> {
            out.writeByte('S');
            cstring(out, "ERROR");
            out.writeByte('C');
            cstring(out, state);
            out.writeByte('M');
            cstring(out, text == null ? "Unknown error" : text);
            out.writeByte(0);
          });
      ctx.flush();
    }

    void message(
        ChannelHandlerContext ctx, char type, java.util.function.Consumer<ByteBuf> writer) {
      ByteBuf body = ctx.alloc().buffer();
      writer.accept(body);
      ByteBuf msg = ctx.alloc().buffer(body.readableBytes() + 5);
      msg.writeByte(type);
      msg.writeInt(body.readableBytes() + 4);
      msg.writeBytes(body);
      body.release();
      ctx.write(msg);
    }

    void ensureAuth() {
      if (!authenticated) throw new SecurityException("Not authenticated");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      log.warn("pgwire connection closed after protocol error remote={}", ctx.channel().remoteAddress(), cause);
      ctx.close();
    }

    private static boolean secureEquals(String expected, String supplied) {
      if (expected == null || supplied == null) return false;
      return MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    static String cstring(ByteBuf b) {
      int start = b.readerIndex(), end = start;
      while (end < b.writerIndex() && b.getByte(end) != 0) end++;
      byte[] bytes = new byte[end - start];
      b.readBytes(bytes);
      if (b.isReadable()) b.readByte();
      return new String(bytes, StandardCharsets.UTF_8);
    }

    static void cstring(ByteBuf b, String s) {
      b.writeCharSequence(s, StandardCharsets.UTF_8);
      b.writeByte(0);
    }

    record Portal(PgQueryService.Prepared prepared, List<Object> parameters) {}
  }
}
