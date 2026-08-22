package com.acme.semantic.pgwire;

import com.acme.semantic.auth.AuthenticationService;
import com.acme.semantic.compiler.SqlCompilationException;
import com.acme.semantic.config.SemanticProperties;
import com.acme.semantic.core.SemanticErrorCode;
import com.acme.semantic.core.SemanticException;
import com.acme.semantic.core.SemanticPrincipal;
import com.acme.semantic.execution.QueryExecutionException;
import com.acme.semantic.execution.QueryResult;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PgWireServer {
  private static final Logger log = LoggerFactory.getLogger(PgWireServer.class);
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final int PROTOCOL_V3 = 196608;
  private static final int SSL_REQUEST = 80877103;
  private static final int GSSENC_REQUEST = 80877104;
  private static final int CANCEL_REQUEST = 80877102;
  private static final ConcurrentMap<Integer, Handler> BACKENDS = new ConcurrentHashMap<>();

  private final SemanticProperties properties;
  private final PgQueryService service;
  private final AuthenticationService authentication;
  private EventLoopGroup boss;
  private EventLoopGroup workers;
  private EventExecutorGroup queryWorkers;
  private Channel channel;

  public PgWireServer(
      SemanticProperties properties,
      PgQueryService service,
      AuthenticationService authentication) {
    this.properties = properties;
    this.service = service;
    this.authentication = authentication;
  }

  @PostConstruct
  public void start() {
    if (!properties.pgwire().enabled()) return;
    boss = new NioEventLoopGroup(1);
    workers = new NioEventLoopGroup();
    queryWorkers =
        new DefaultEventExecutorGroup(
            Math.max(2, Math.min(16, Runtime.getRuntime().availableProcessors())));
    channel =
        new ServerBootstrap()
            .group(boss, workers)
            .channel(NioServerSocketChannel.class)
            .childOption(ChannelOption.AUTO_READ, true)
            .childHandler(
                new ChannelInitializer<SocketChannel>() {
                  @Override
                  protected void initChannel(SocketChannel socket) {
                    socket
                        .pipeline()
                        .addLast(new PgDecoder(properties.pgwire().maxFrameBytes()))
                        .addLast(
                            queryWorkers,
                            "pgwire-handler",
                            new Handler(properties.pgwire(), service, authentication));
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
    if (queryWorkers != null) queryWorkers.shutdownGracefully().syncUninterruptibly();
    if (workers != null) workers.shutdownGracefully();
    if (boss != null) boss.shutdownGracefully();
  }

  record Message(byte type, ByteBuf payload) {}

  static final class PgDecoder extends ByteToMessageDecoder {
    private final int maxFrameBytes;
    private boolean startup = true;
    private boolean failed;

    PgDecoder(int maxFrameBytes) {
      this.maxFrameBytes = maxFrameBytes;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
      if (failed) {
        in.skipBytes(in.readableBytes());
        return;
      }
      if (startup) decodeStartup(ctx, in, out);
      else decodeTyped(ctx, in, out);
    }

    private void decodeStartup(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
      if (in.readableBytes() < 4) return;
      int length = in.getInt(in.readerIndex());
      if (!validLength(ctx, length, 8)) return;
      if (in.readableBytes() < length) return;
      in.readInt();
      ByteBuf payload = in.readRetainedSlice(length - 4);
      int protocol = payload.getInt(payload.readerIndex());
      if (protocol == SSL_REQUEST) {
        out.add(new Message((byte) 'S', payload));
      } else if (protocol == GSSENC_REQUEST) {
        out.add(new Message((byte) 'G', payload));
      } else if (protocol == CANCEL_REQUEST) {
        out.add(new Message((byte) 'K', payload));
      } else {
        startup = false;
        out.add(new Message((byte) 0, payload));
      }
    }

    private void decodeTyped(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
      if (in.readableBytes() < 5) return;
      byte type = in.getByte(in.readerIndex());
      int length = in.getInt(in.readerIndex() + 1);
      if (!validLength(ctx, length, 4)) return;
      if (in.readableBytes() < length + 1) return;
      in.skipBytes(5);
      out.add(new Message(type, in.readRetainedSlice(length - 4)));
    }

    private boolean validLength(ChannelHandlerContext ctx, int length, int minimum) {
      if (length >= minimum && length <= maxFrameBytes) return true;
      failed = true;
      String message =
          length < minimum
              ? "Invalid PostgreSQL protocol frame length"
              : "PostgreSQL protocol frame exceeds " + maxFrameBytes + " bytes";
      protocolErrorAndClose(ctx, "08P01", message);
      return false;
    }
  }

  static final class Handler extends SimpleChannelInboundHandler<Message> {
    private final SemanticProperties.Pgwire config;
    private final PgQueryService service;
    private final AuthenticationService authentication;
    private final Map<String, PgQueryService.Prepared> statements = new HashMap<>();
    private final Map<String, Portal> portals = new HashMap<>();
    private final int backendProcessId = SECURE_RANDOM.nextInt(Integer.MAX_VALUE - 1) + 1;
    private final int backendSecret = SECURE_RANDOM.nextInt();
    private boolean authenticated;
    private boolean awaitingSync;
    private String startupUser;
    private SemanticPrincipal sessionPrincipal;
    private String currentSchema;
    private String searchPath;
    private final Map<String, String> sessionSettings = new HashMap<>();
    private volatile Thread executingThread;
    private TransactionStatus transactionStatus = TransactionStatus.IDLE;

    Handler(
        SemanticProperties.Pgwire config,
        PgQueryService service,
        AuthenticationService authentication) {
      this.config = config;
      this.service = service;
      this.authentication = authentication;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message message) {
      try {
        if (awaitingSync
            && message.type != 'S'
            && message.type != 'X'
            && message.type != 'H') return;
        dispatch(ctx, message);
      } catch (Exception exception) {
        handleFailure(ctx, message.type, exception);
      } finally {
        if (message.payload.refCnt() > 0) message.payload.release();
      }
    }

    private void dispatch(ChannelHandlerContext ctx, Message message) {
      if (message.type == 'S' && isNegotiation(message.payload, SSL_REQUEST)) {
        ctx.writeAndFlush(ctx.alloc().buffer(1).writeByte('N'));
        return;
      }
      if (message.type == 'G' && isNegotiation(message.payload, GSSENC_REQUEST)) {
        ctx.writeAndFlush(ctx.alloc().buffer(1).writeByte('N'));
        return;
      }
      if (message.type == 'K' && isCancelRequest(message.payload)) {
        cancel(message.payload);
        ctx.close();
        return;
      }
      if (message.type == 0) {
        startup(ctx, message.payload);
        return;
      }

      switch (message.type) {
        case 'p' -> password(ctx, message.payload);
        case 'Q' -> simple(ctx, cstring(message.payload));
        case 'P' -> parse(ctx, message.payload);
        case 'B' -> bind(ctx, message.payload);
        case 'D' -> describe(ctx, message.payload);
        case 'E' -> execute(ctx, message.payload);
        case 'C' -> close(ctx, message.payload);
        case 'S' -> sync(ctx);
        case 'X' -> ctx.close();
        case 'H' -> ctx.flush();
        default -> throw ProtocolException.fatal(
            "Unsupported protocol message: " + (char) message.type);
      }
    }

    private boolean isNegotiation(ByteBuf payload, int code) {
      return payload.readableBytes() == 4 && payload.getInt(payload.readerIndex()) == code;
    }

    private boolean isCancelRequest(ByteBuf payload) {
      return payload.readableBytes() == 12
          && payload.getInt(payload.readerIndex()) == CANCEL_REQUEST;
    }

    private void cancel(ByteBuf payload) {
      int index = payload.readerIndex();
      int processId = payload.getInt(index + 4);
      int secret = payload.getInt(index + 8);
      Handler target = BACKENDS.get(processId);
      if (target != null && target.backendSecret == secret) target.cancelExecution();
    }

    private void cancelExecution() {
      Thread thread = executingThread;
      if (thread != null) thread.interrupt();
    }

    private void startup(ChannelHandlerContext ctx, ByteBuf payload) {
      if (payload.readableBytes() < 4) throw ProtocolException.fatal("Malformed StartupMessage");
      int protocol = payload.readInt();
      if (protocol != PROTOCOL_V3) {
        error(ctx, "0A000", "Unsupported PostgreSQL protocol version", true);
        ctx.close();
        return;
      }
      Map<String, String> parameters = new HashMap<>();
      while (payload.isReadable()) {
        String key = cstring(payload);
        if (key.isEmpty()) break;
        if (!payload.isReadable()) throw ProtocolException.fatal("Malformed StartupMessage");
        parameters.put(key, cstring(payload));
      }
      startupUser = parameters.get("user");
      String database = parameters.getOrDefault("database", startupUser);
      if (!"semantic".equals(database)) {
        log.warn(
            "pgwire startup rejected remote={} user={} database={} reason=invalid-database",
            ctx.channel().remoteAddress(),
            startupUser,
            database);
        error(ctx, "3D000", "Database does not exist", true);
        ctx.close();
        return;
      }
      message(ctx, 'R', out -> out.writeInt(3));
      ctx.flush();
    }

    private void password(ChannelHandlerContext ctx, ByteBuf payload) {
      String supplied = cstring(payload);
      var local = authentication.authenticatePassword(startupUser, supplied);
      var serviceAccount = authentication.authenticateApiKey(supplied);
      sessionPrincipal =
          local
              .filter(principal -> sameUser(principal.username(), startupUser))
              .or(
                  () ->
                      serviceAccount.filter(
                          principal -> sameUser(principal.username(), startupUser)))
              .orElse(null);
      if (sessionPrincipal == null) {
        log.warn(
            "pgwire authentication rejected remote={} user={} reason=invalid-credentials-or-account-state",
            ctx.channel().remoteAddress(),
            startupUser);
        error(
            ctx,
            "28P01",
            "password authentication failed for user \"" + startupUser + "\"",
            true);
        ctx.close();
        return;
      }
      log.info(
          "pgwire authentication accepted remote={} user={} provider={}",
          ctx.channel().remoteAddress(),
          startupUser,
          sessionPrincipal.provider());
      authenticated = true;
      BACKENDS.put(backendProcessId, this);
      currentSchema = service.defaultSchemaName(sessionPrincipal);
      searchPath = currentSchema == null ? "" : currentSchema;
      sessionSettings.put("client_encoding", "UTF8");
      sessionSettings.put("standard_conforming_strings", "on");
      sessionSettings.put("timezone", "UTC");
      message(ctx, 'R', out -> out.writeInt(0));
      parameter(ctx, "server_version", "14.0");
      parameter(ctx, "server_version_num", "140000");
      parameter(ctx, "server_encoding", "UTF8");
      parameter(ctx, "client_encoding", "UTF8");
      parameter(ctx, "DateStyle", "ISO, MDY");
      parameter(ctx, "TimeZone", "UTC");
      parameter(ctx, "integer_datetimes", "on");
      parameter(ctx, "standard_conforming_strings", "on");
      message(
          ctx,
          'K',
          out -> {
            out.writeInt(backendProcessId);
            out.writeInt(backendSecret);
          });
      ready(ctx);
    }

    private void simple(ChannelHandlerContext ctx, String sql) {
      ensureAuthenticated();
      List<String> queries = splitStatements(sql);
      if (queries.isEmpty()) {
        emptyQuery(ctx);
        ready(ctx);
        return;
      }
      for (String query : queries) {
        PgQueryService.SessionSettings session = session();
        PgQueryService.Prepared prepared = service.prepare(query, session);
        executeSimple(ctx, prepared, session);
      }
      ready(ctx);
    }

    private void executeSimple(
        ChannelHandlerContext ctx,
        PgQueryService.Prepared prepared,
        PgQueryService.SessionSettings session) {
      if (prepared.empty()) {
        emptyQuery(ctx);
        return;
      }
      String tag = transitionBeforeExecution(prepared);
      applySessionCommand(prepared.sql(), session.principal());
      QueryResult result = executeQuery(prepared, List.of(), session);
      sendResult(ctx, result, true, new short[0], 0, 0, tag);
    }

    private void parse(ChannelHandlerContext ctx, ByteBuf payload) {
      ensureAuthenticated();
      String name = cstring(payload);
      String sql = cstring(payload);
      requireReadable(payload, 2);
      int count = payload.readUnsignedShort();
      requireReadable(payload, count * 4);
      int[] parameterOids = new int[count];
      for (int i = 0; i < count; i++) parameterOids[i] = payload.readInt();
      requireExhausted(payload);
      if (!statements.containsKey(name) && statements.size() >= config.maxPreparedStatements())
        throw new ProtocolException("54000", "Too many prepared statements", false);
      statements.put(name, service.prepare(sql, session()).withParameterOids(parameterOids));
      if (name.isEmpty()) portals.remove("");
      message(ctx, '1', ignored -> {});
      ctx.flush();
    }

    private void bind(ChannelHandlerContext ctx, ByteBuf payload) {
      ensureAuthenticated();
      String portalName = cstring(payload);
      String statementName = cstring(payload);
      PgQueryService.Prepared prepared = statements.get(statementName);
      if (prepared == null)
        throw new ProtocolException("26000", "Prepared statement does not exist", false);
      service.requireReadable(prepared, session().principal());

      int formatCount = readUnsignedShort(payload);
      short[] parameterFormats = readFormats(payload, formatCount);
      int count = readUnsignedShort(payload);
      if (count != prepared.parameterCount())
        throw ProtocolException.protocol("Bind parameter count does not match Parse");
      validateFormatCount(parameterFormats, count, "parameter");
      List<Object> parameters = new ArrayList<>(count);
      for (int i = 0; i < count; i++) {
        requireReadable(payload, 4);
        int length = payload.readInt();
        if (length == -1) {
          parameters.add(null);
          continue;
        }
        if (length < -1) throw ProtocolException.protocol("Invalid Bind parameter length");
        requireReadable(payload, length);
        short format = format(parameterFormats, i);
        parameters.add(decodeParameter(payload, length, format, prepared.parameterOid(i)));
      }

      int resultFormatCount = readUnsignedShort(payload);
      short[] resultFormats = readFormats(payload, resultFormatCount);
      validateFormatCount(resultFormats, prepared.columns().size(), "result");
      requireExhausted(payload);
      if (!portals.containsKey(portalName) && portals.size() >= config.maxPreparedStatements())
        throw new ProtocolException("54000", "Too many portals", false);
      portals.put(
          portalName, new Portal(statementName, prepared, parameters, resultFormats));
      message(ctx, '2', ignored -> {});
      ctx.flush();
    }

    private void describe(ChannelHandlerContext ctx, ByteBuf payload) {
      ensureAuthenticated();
      requireReadable(payload, 1);
      byte kind = payload.readByte();
      String name = cstring(payload);
      requireExhausted(payload);
      SemanticPrincipal principal = session().principal();
      if (kind == 'S') {
        PgQueryService.Prepared prepared = statements.get(name);
        if (prepared == null)
          throw new ProtocolException("26000", "Prepared statement does not exist", false);
        service.requireReadable(prepared, principal);
        parameterDescription(ctx, prepared);
        rowDescription(ctx, prepared.columns(), new short[0]);
      } else if (kind == 'P') {
        Portal portal = portals.get(name);
        if (portal == null) throw new ProtocolException("34000", "Portal does not exist", false);
        service.requireReadable(portal.prepared, principal);
        rowDescription(ctx, portal.prepared.columns(), portal.resultFormats);
      } else {
        throw ProtocolException.protocol("Describe target must be S or P");
      }
      ctx.flush();
    }

    private void execute(ChannelHandlerContext ctx, ByteBuf payload) {
      ensureAuthenticated();
      String portalName = cstring(payload);
      requireReadable(payload, 4);
      int maxRows = payload.readInt();
      requireExhausted(payload);
      if (maxRows < 0) throw ProtocolException.protocol("Execute maxRows must not be negative");
      Portal portal = portals.get(portalName);
      if (portal == null) throw new ProtocolException("34000", "Portal does not exist", false);
      PgQueryService.SessionSettings session = session();
      service.requireExecutionAccess(portal.prepared, session.principal());
      if (portal.prepared.empty()) {
        emptyQuery(ctx);
        ctx.flush();
        return;
      }
      if (portal.result == null || portal.prepared.compiled() == null) {
        portal.commandTag = transitionBeforeExecution(portal.prepared);
        applySessionCommand(portal.prepared.sql(), session.principal());
        portal.result = executeQuery(portal.prepared, portal.parameters, session);
      }
      portal.offset =
          sendResult(
              ctx,
              portal.result,
              false,
              portal.resultFormats,
              portal.offset,
              maxRows,
              portal.commandTag);
    }

    private void close(ChannelHandlerContext ctx, ByteBuf payload) {
      ensureAuthenticated();
      requireReadable(payload, 1);
      byte kind = payload.readByte();
      String name = cstring(payload);
      requireExhausted(payload);
      if (kind == 'S') {
        statements.remove(name);
        portals.entrySet().removeIf(entry -> entry.getValue().preparedName.equals(name));
      } else if (kind == 'P') {
        portals.remove(name);
      } else {
        throw ProtocolException.protocol("Close target must be S or P");
      }
      message(ctx, '3', ignored -> {});
      ctx.flush();
    }

    private void sync(ChannelHandlerContext ctx) {
      ensureAuthenticated();
      awaitingSync = false;
      ready(ctx);
    }

    private String transitionBeforeExecution(PgQueryService.Prepared prepared) {
      String tag = prepared.commandTag();
      if (transactionStatus == TransactionStatus.FAILED) {
        if ("ROLLBACK".equals(tag) || "COMMIT".equals(tag)) {
          transactionStatus = TransactionStatus.IDLE;
          return "ROLLBACK";
        }
        throw new ProtocolException(
            "25P02", "Current transaction is aborted; commands ignored until ROLLBACK", false);
      }
      if ("BEGIN".equals(tag)) transactionStatus = TransactionStatus.TRANSACTION;
      else if ("COMMIT".equals(tag) || "ROLLBACK".equals(tag))
        transactionStatus = TransactionStatus.IDLE;
      return tag;
    }

    private PgQueryService.SessionSettings session() {
      SemanticPrincipal current =
          authentication
              .refreshPrincipal(sessionPrincipal)
              .orElseThrow(
                  () ->
                      new ProtocolException(
                          "28000", "Session authorization is no longer valid", true));
      sessionPrincipal = current;
      if (currentSchema == null || !service.hasSchema(current, currentSchema)) {
        currentSchema = service.defaultSchemaName(current);
        searchPath = currentSchema == null ? "" : currentSchema;
        sessionSettings.put("search_path", searchPath);
      }
      return new PgQueryService.SessionSettings(
          currentSchema, searchPath, sessionSettings, current);
    }

    private QueryResult executeQuery(
        PgQueryService.Prepared prepared,
        List<Object> parameters,
        PgQueryService.SessionSettings session) {
      executingThread = Thread.currentThread();
      try {
        return service.execute(prepared, parameters, session);
      } finally {
        executingThread = null;
        Thread.interrupted();
      }
    }

    private void applySessionCommand(String sql, SemanticPrincipal principal) {
      String normalized = sql.strip().replaceFirst(";\\s*$", "");
      var searchPathMatcher =
          Pattern.compile(
                  "(?is)^set\\s+(?:session\\s+|local\\s+)?search_path\\s*(?:to|=)\\s*(.+)$")
              .matcher(normalized);
      if (searchPathMatcher.matches()) {
        List<String> requested = parseSearchPath(searchPathMatcher.group(1));
        String resolved =
            requested.stream()
                .filter(schema -> service.hasSchema(principal, schema))
                .findFirst()
                .orElseThrow(
                    () -> new ProtocolException("3F000", "No valid schema in search_path", false));
        currentSchema = resolved;
        searchPath = String.join(", ", requested);
        sessionSettings.put("search_path", searchPath);
        return;
      }
      var settingMatcher =
          Pattern.compile(
                  "(?is)^set\\s+(?:session\\s+|local\\s+)?([a-z_][a-z0-9_]*)\\s*(?:to|=)\\s*(.+)$")
              .matcher(normalized);
      if (settingMatcher.matches()) {
        String key = settingMatcher.group(1).toLowerCase(Locale.ROOT);
        String value = unquoteSetting(settingMatcher.group(2).strip());
        sessionSettings.put(key, value);
      }
    }

    private List<String> parseSearchPath(String value) {
      List<String> schemas = new ArrayList<>();
      for (String part : value.split(",")) {
        String schema = unquoteSetting(part.strip());
        if (!schema.isEmpty() && !schema.equalsIgnoreCase("$user")) schemas.add(schema);
      }
      return schemas;
    }

    private String unquoteSetting(String value) {
      if (value.length() >= 2
          && ((value.startsWith("'") && value.endsWith("'"))
              || (value.startsWith("\"") && value.endsWith("\""))))
        return value.substring(1, value.length() - 1).replace("''", "'").replace("\"\"", "\"");
      return value;
    }

    private int sendResult(
        ChannelHandlerContext ctx,
        QueryResult result,
        boolean describe,
        short[] formats,
        int offset,
        int maxRows,
        String commandTag) {
      if (!result.columns().isEmpty() && describe)
        rowDescriptionFromResult(ctx, result.columns(), formats);

      int end = result.rows().size();
      if (maxRows > 0) end = Math.min(end, offset + maxRows);
      for (int rowIndex = offset; rowIndex < end; rowIndex++)
        dataRow(ctx, result.columns(), result.rows().get(rowIndex), formats);

      if (maxRows > 0 && end < result.rows().size()) {
        message(ctx, 's', ignored -> {});
      } else {
        String tag = commandTag;
        if (tag == null) tag = result.columns().isEmpty() ? "SET" : "SELECT " + end;
        String finalTag = tag;
        message(ctx, 'C', out -> cstring(out, finalTag));
      }
      ctx.flush();
      return end;
    }

    private void dataRow(
        ChannelHandlerContext ctx,
        List<QueryResult.Column> columns,
        List<Object> row,
        short[] formats) {
      if (row.size() != columns.size())
        throw new ProtocolException("XX000", "Query result column count mismatch", false);
      message(
          ctx,
          'D',
          out -> {
            out.writeShort(row.size());
            for (int i = 0; i < row.size(); i++) {
              Object value = row.get(i);
              if (value == null) {
                out.writeInt(-1);
              } else {
                int oid = PgTypeMapper.oid(columns.get(i).typeName());
                byte[] bytes =
                    format(formats, i) == 1
                        ? encodeBinary(value, oid)
                        : encodeText(value, oid);
                out.writeInt(bytes.length);
                out.writeBytes(bytes);
              }
            }
          });
    }

    private void parameterDescription(ChannelHandlerContext ctx, PgQueryService.Prepared prepared) {
      message(
          ctx,
          't',
          out -> {
            out.writeShort(prepared.parameterCount());
            for (int i = 0; i < prepared.parameterCount(); i++)
              out.writeInt(prepared.parameterOid(i));
          });
    }

    private void rowDescriptionFromResult(
        ChannelHandlerContext ctx, List<QueryResult.Column> columns, short[] formats) {
      message(
          ctx,
          'T',
          out -> {
            out.writeShort(columns.size());
            for (int i = 0; i < columns.size(); i++) {
              QueryResult.Column column = columns.get(i);
              writeField(out, column.name(), column.typeName(), format(formats, i));
            }
          });
    }

    private void rowDescription(
        ChannelHandlerContext ctx,
        List<com.acme.semantic.compiler.CompiledQuery.Column> columns,
        short[] formats) {
      if (columns.isEmpty()) {
        message(ctx, 'n', ignored -> {});
        return;
      }
      message(
          ctx,
          'T',
          out -> {
            out.writeShort(columns.size());
            for (int i = 0; i < columns.size(); i++) {
              var column = columns.get(i);
              writeField(out, column.name(), column.semanticType(), format(formats, i));
            }
          });
    }

    private void writeField(ByteBuf out, String name, String type, short format) {
      cstring(out, name);
      out.writeInt(0);
      out.writeShort(0);
      int oid = PgTypeMapper.oid(type);
      out.writeInt(oid);
      out.writeShort(PgTypeMapper.size(oid));
      out.writeInt(-1);
      out.writeShort(format);
    }

    private Object decodeParameter(ByteBuf payload, int length, short format, int oid) {
      byte[] bytes = new byte[length];
      payload.readBytes(bytes);
      if (format == 0) return decodeTextParameter(new String(bytes, StandardCharsets.UTF_8), oid);
      if (format != 1) throw ProtocolException.protocol("Unknown parameter format: " + format);
      ByteBuf value = io.netty.buffer.Unpooled.wrappedBuffer(bytes);
      try {
        return switch (oid) {
          case 16 -> requireBinaryLength(value, 1).readByte() != 0;
          case 20 -> requireBinaryLength(value, 8).readLong();
          case 21 -> requireBinaryLength(value, 2).readShort();
          case 23 -> requireBinaryLength(value, 4).readInt();
          case 700 -> Float.intBitsToFloat(requireBinaryLength(value, 4).readInt());
          case 701 -> Double.longBitsToDouble(requireBinaryLength(value, 8).readLong());
          case 2950 ->
              new UUID(
                  requireBinaryLength(value, 16).readLong(),
                  value.readLong());
          default -> bytes;
        };
      } finally {
        value.release();
      }
    }

    private ByteBuf requireBinaryLength(ByteBuf value, int expected) {
      if (value.readableBytes() != expected)
        throw ProtocolException.protocol("Invalid binary parameter length");
      return value;
    }

    private Object decodeTextParameter(String value, int oid) {
      try {
        return switch (oid) {
          case 16 -> value.equalsIgnoreCase("t") || Boolean.parseBoolean(value);
          case 20 -> Long.parseLong(value);
          case 21 -> Short.parseShort(value);
          case 23 -> Integer.parseInt(value);
          case 700 -> Float.parseFloat(value);
          case 701 -> Double.parseDouble(value);
          case 1700 -> new BigDecimal(value);
          case 1082 -> LocalDate.parse(value);
          case 1083 -> LocalTime.parse(value);
          case 1114 -> LocalDateTime.parse(value.replace(' ', 'T'));
          case 1184 -> OffsetDateTime.parse(value.replace(' ', 'T'));
          case 2950 -> UUID.fromString(value);
          default -> value;
        };
      } catch (RuntimeException exception) {
        throw new ProtocolException("22P02", "Invalid text representation for parameter", false);
      }
    }

    private byte[] encodeText(Object value, int oid) {
      if (oid == 16)
        return ((value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString())) ? "t" : "f")
            .getBytes(StandardCharsets.UTF_8);
      if (oid == 17 && value instanceof byte[] bytes)
        return ("\\x" + java.util.HexFormat.of().formatHex(bytes)).getBytes(StandardCharsets.UTF_8);
      if (value instanceof BigDecimal decimal)
        return decimal.toPlainString().getBytes(StandardCharsets.UTF_8);
      return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] encodeBinary(Object value, int oid) {
      try {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        switch (oid) {
          case 16 -> out.writeByte(asBoolean(value) ? 1 : 0);
          case 20 -> out.writeLong(((Number) value).longValue());
          case 21 -> out.writeShort(((Number) value).shortValue());
          case 23 -> out.writeInt(((Number) value).intValue());
          case 700 -> out.writeInt(Float.floatToIntBits(((Number) value).floatValue()));
          case 701 -> out.writeLong(Double.doubleToLongBits(((Number) value).doubleValue()));
          case 1700 -> writeNumeric(out, value instanceof BigDecimal d ? d : new BigDecimal(value.toString()));
          case 1082 -> out.writeInt((int) ChronoUnit.DAYS.between(LocalDate.of(2000, 1, 1), asDate(value)));
          case 1083 -> out.writeLong(asTime(value).toNanoOfDay() / 1_000);
          case 1114 -> out.writeLong(timestampMicros(asLocalDateTime(value).toInstant(ZoneOffset.UTC)));
          case 1184 -> out.writeLong(timestampMicros(asInstant(value)));
          case 2950 -> {
            UUID uuid = value instanceof UUID u ? u : UUID.fromString(value.toString());
            out.writeLong(uuid.getMostSignificantBits());
            out.writeLong(uuid.getLeastSignificantBits());
          }
          case 17 -> out.write(value instanceof byte[] binary ? binary : value.toString().getBytes(StandardCharsets.UTF_8));
          case 3802 -> {
            out.writeByte(1);
            out.write(value.toString().getBytes(StandardCharsets.UTF_8));
          }
          default -> out.write(value.toString().getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
      } catch (Exception exception) {
        throw new ProtocolException("22000", "Cannot encode PostgreSQL binary value", false);
      }
    }

    private void writeNumeric(DataOutputStream out, BigDecimal value) throws Exception {
      int scale = Math.max(0, value.scale());
      String plain = value.abs().toPlainString();
      String[] parts = plain.split("\\.", -1);
      String integer = parts[0];
      String fraction = parts.length == 2 ? parts[1] : "";
      int integerGroups = (integer.length() + 3) / 4;
      integer = "0".repeat(integerGroups * 4 - integer.length()) + integer;
      int fractionGroups = (fraction.length() + 3) / 4;
      fraction = fraction + "0".repeat(fractionGroups * 4 - fraction.length());
      List<Integer> groups = new ArrayList<>();
      for (int i = 0; i < integer.length(); i += 4)
        groups.add(Integer.parseInt(integer.substring(i, i + 4)));
      for (int i = 0; i < fraction.length(); i += 4)
        groups.add(Integer.parseInt(fraction.substring(i, i + 4)));
      int weight = integerGroups - 1;
      while (!groups.isEmpty() && groups.getFirst() == 0) {
        groups.removeFirst();
        weight--;
      }
      while (!groups.isEmpty() && groups.getLast() == 0) groups.removeLast();
      out.writeShort(groups.size());
      out.writeShort(weight);
      out.writeShort(value.signum() < 0 ? 0x4000 : 0x0000);
      out.writeShort(scale);
      for (int group : groups) out.writeShort(group);
    }

    private boolean asBoolean(Object value) {
      return value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString());
    }

    private LocalDate asDate(Object value) {
      if (value instanceof LocalDate date) return date;
      if (value instanceof java.sql.Date date) return date.toLocalDate();
      return LocalDate.parse(value.toString());
    }

    private LocalTime asTime(Object value) {
      if (value instanceof LocalTime time) return time;
      if (value instanceof java.sql.Time time) return time.toLocalTime();
      return LocalTime.parse(value.toString());
    }

    private LocalDateTime asLocalDateTime(Object value) {
      if (value instanceof LocalDateTime dateTime) return dateTime;
      if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
      return LocalDateTime.parse(value.toString().replace(' ', 'T'));
    }

    private Instant asInstant(Object value) {
      if (value instanceof Instant instant) return instant;
      if (value instanceof OffsetDateTime dateTime) return dateTime.toInstant();
      if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
      return OffsetDateTime.parse(value.toString().replace(' ', 'T')).toInstant();
    }

    private long timestampMicros(Instant instant) {
      Instant epoch = Instant.parse("2000-01-01T00:00:00Z");
      long seconds = instant.getEpochSecond() - epoch.getEpochSecond();
      return seconds * 1_000_000 + instant.getNano() / 1_000;
    }

    private void emptyQuery(ChannelHandlerContext ctx) {
      message(ctx, 'I', ignored -> {});
      ctx.flush();
    }

    private void parameter(ChannelHandlerContext ctx, String key, String value) {
      message(
          ctx,
          'S',
          out -> {
            cstring(out, key);
            cstring(out, value);
          });
    }

    private void ready(ChannelHandlerContext ctx) {
      message(ctx, 'Z', out -> out.writeByte(transactionStatus.code));
      ctx.flush();
    }

    private void error(
        ChannelHandlerContext ctx, String state, String text, boolean fatal) {
      message(
          ctx,
          'E',
          out -> {
            out.writeByte('S');
            cstring(out, fatal ? "FATAL" : "ERROR");
            out.writeByte('C');
            cstring(out, state);
            out.writeByte('M');
            cstring(out, text == null ? "Unknown error" : text);
            out.writeByte(0);
          });
      ctx.flush();
    }

    private void handleFailure(ChannelHandlerContext ctx, byte messageType, Exception exception) {
      String state;
      String text;
      boolean fatal = exception instanceof ProtocolException protocol && protocol.fatal;
      boolean terminating = fatal || messageType == 0 || messageType == 'p';
      if (exception instanceof ProtocolException protocol) {
        state = protocol.state;
        text = protocol.getMessage();
      } else if (exception instanceof SqlCompilationException compilation) {
        state = compilation.sqlState();
        text = compilation.getMessage();
      } else if (exception instanceof QueryExecutionException execution) {
        state = execution.sqlState();
        text = "Query execution failed";
        log.warn("pgwire query execution failed remote={}", ctx.channel().remoteAddress(), exception);
      } else if (exception instanceof SemanticException semantic) {
        if (semantic.code() == SemanticErrorCode.SEMANTIC_OBJECT_NOT_FOUND) {
          state = "42P01";
          text = "Unknown semantic object";
        } else if (semantic.code() == SemanticErrorCode.ACCESS_DENIED) {
          state = "42501";
          text = semantic.getMessage();
        } else {
          state = "XX000";
          text = "Semantic query failed";
        }
      } else {
        state = "XX000";
        text = "Internal server error";
        log.warn("pgwire request failed remote={}", ctx.channel().remoteAddress(), exception);
      }

      if (transactionStatus == TransactionStatus.TRANSACTION)
        transactionStatus = TransactionStatus.FAILED;
      error(ctx, state, text, terminating);
      if (terminating) {
        ctx.close();
      } else if (isExtended(messageType)) {
        awaitingSync = true;
      } else {
        ready(ctx);
      }
    }

    private boolean isExtended(byte type) {
      return type == 'P' || type == 'B' || type == 'D' || type == 'E' || type == 'C';
    }

    private void message(ChannelHandlerContext ctx, char type, Consumer<ByteBuf> writer) {
      ByteBuf body = ctx.alloc().buffer();
      try {
        writer.accept(body);
        ByteBuf result = ctx.alloc().buffer(body.readableBytes() + 5);
        result.writeByte(type);
        result.writeInt(body.readableBytes() + 4);
        result.writeBytes(body);
        ctx.write(result);
      } finally {
        body.release();
      }
    }

    private void ensureAuthenticated() {
      if (!authenticated) throw ProtocolException.fatal("Client is not authenticated");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      log.warn(
          "pgwire connection closed after transport error remote={}",
          ctx.channel().remoteAddress(),
          cause);
      ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
      BACKENDS.remove(backendProcessId, this);
    }

    private static boolean sameUser(String expected, String supplied) {
      return expected != null && supplied != null && expected.equalsIgnoreCase(supplied);
    }

    private static int readUnsignedShort(ByteBuf payload) {
      requireReadable(payload, 2);
      return payload.readUnsignedShort();
    }

    private static short[] readFormats(ByteBuf payload, int count) {
      requireReadable(payload, count * 2);
      short[] formats = new short[count];
      for (int i = 0; i < count; i++) {
        formats[i] = payload.readShort();
        if (formats[i] != 0 && formats[i] != 1)
          throw ProtocolException.protocol("Format code must be zero or one");
      }
      return formats;
    }

    private static void validateFormatCount(short[] formats, int values, String kind) {
      if (formats.length != 0 && formats.length != 1 && formats.length != values)
        throw ProtocolException.protocol("Invalid " + kind + " format count");
    }

    private static short format(short[] formats, int index) {
      if (formats.length == 0) return 0;
      return formats.length == 1 ? formats[0] : formats[index];
    }

    private static void requireReadable(ByteBuf payload, int bytes) {
      if (bytes < 0 || payload.readableBytes() < bytes)
        throw ProtocolException.protocol("Malformed PostgreSQL protocol message");
    }

    private static void requireExhausted(ByteBuf payload) {
      if (payload.isReadable())
        throw ProtocolException.protocol("Unexpected bytes in PostgreSQL protocol message");
    }

    private static String cstring(ByteBuf payload) {
      int start = payload.readerIndex();
      int end = start;
      while (end < payload.writerIndex() && payload.getByte(end) != 0) end++;
      if (end == payload.writerIndex())
        throw ProtocolException.protocol("Unterminated PostgreSQL protocol string");
      byte[] bytes = new byte[end - start];
      payload.readBytes(bytes);
      payload.readByte();
      return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void cstring(ByteBuf payload, String value) {
      payload.writeCharSequence(value, StandardCharsets.UTF_8);
      payload.writeByte(0);
    }

    private static List<String> splitStatements(String sql) {
      List<String> statements = new ArrayList<>();
      int start = 0;
      boolean single = false;
      boolean quoted = false;
      boolean lineComment = false;
      boolean blockComment = false;
      for (int i = 0; i < sql.length(); i++) {
        char current = sql.charAt(i);
        char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
        if (lineComment) {
          if (current == '\n' || current == '\r') lineComment = false;
          continue;
        }
        if (blockComment) {
          if (current == '*' && next == '/') {
            blockComment = false;
            i++;
          }
          continue;
        }
        if (!single && !quoted && current == '-' && next == '-') {
          lineComment = true;
          i++;
          continue;
        }
        if (!single && !quoted && current == '/' && next == '*') {
          blockComment = true;
          i++;
          continue;
        }
        if (!quoted && current == '\'') {
          if (single && next == '\'') i++;
          else single = !single;
          continue;
        }
        if (!single && current == '"') {
          if (quoted && next == '"') i++;
          else quoted = !quoted;
          continue;
        }
        if (!single && !quoted && current == ';') {
          String statement = sql.substring(start, i).strip();
          if (!statement.isEmpty()) statements.add(statement);
          start = i + 1;
        }
      }
      if (single || quoted || blockComment)
        throw new ProtocolException("42601", "Unterminated SQL quote or comment", false);
      String last = sql.substring(start).strip();
      if (!last.isEmpty()) statements.add(last);
      return statements;
    }

    private enum TransactionStatus {
      IDLE((byte) 'I'),
      TRANSACTION((byte) 'T'),
      FAILED((byte) 'E');

      private final byte code;

      TransactionStatus(byte code) {
        this.code = code;
      }
    }

    private static final class Portal {
      private final PgQueryService.Prepared prepared;
      private final List<Object> parameters;
      private final short[] resultFormats;
      private final String preparedName;
      private QueryResult result;
      private String commandTag;
      private int offset;

      private Portal(
          String preparedName,
          PgQueryService.Prepared prepared,
          List<Object> parameters,
          short[] resultFormats) {
        this.prepared = prepared;
        this.parameters = new ArrayList<>(parameters);
        this.resultFormats = resultFormats.clone();
        this.preparedName = preparedName;
      }
    }
  }

  static final class ProtocolException extends RuntimeException {
    private final String state;
    private final boolean fatal;

    ProtocolException(String state, String message, boolean fatal) {
      super(message);
      this.state = state;
      this.fatal = fatal;
    }

    static ProtocolException protocol(String message) {
      return new ProtocolException("08P01", message, false);
    }

    static ProtocolException fatal(String message) {
      return new ProtocolException("08P01", message, true);
    }
  }

  private static void protocolErrorAndClose(
      ChannelHandlerContext ctx, String state, String text) {
    ByteBuf body = ctx.alloc().buffer();
    body.writeByte('S');
    writeCString(body, "FATAL");
    body.writeByte('C');
    writeCString(body, state);
    body.writeByte('M');
    writeCString(body, text);
    body.writeByte(0);
    ByteBuf message = ctx.alloc().buffer(body.readableBytes() + 5);
    message.writeByte('E');
    message.writeInt(body.readableBytes() + 4);
    message.writeBytes(body);
    body.release();
    ctx.writeAndFlush(message).addListener(ChannelFutureListener.CLOSE);
  }

  private static void writeCString(ByteBuf output, String value) {
    output.writeCharSequence(value, StandardCharsets.UTF_8);
    output.writeByte(0);
  }
}
