// Checks Discord mute commands, permission gates, main-thread dispatch and safe moderation log payloads without networking.
package Extinction;

import arc.Core;
import arc.mock.MockApplication;
import arc.util.serialization.Jval;
import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

public final class ModerationDiscordTest {
    static int checks;
    static void require(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }

    public static void main(String[] args) throws Exception {
        checkDefinitions();
        checkDispatch();
        checkLog();
        System.out.println("Discord moderation checks passed: " + checks);
    }

    static void checkDefinitions() throws Exception {
        Method definitions = DiscordApi.class.getDeclaredMethod("definitions");
        definitions.setAccessible(true);
        Jval commands = Jval.read((String) definitions.invoke(null));
        Set<String> names = new HashSet<>();
        for (Jval command : commands.asArray()) {
            String name = command.getString("name");
            names.add(name);
            if (!Set.of("mute", "unmute").contains(name)) continue;
            require(command.getString("default_member_permissions").equals("4"), name + " has staff visibility gate");
            require(!command.getBool("dm_permission", true), name + " is guild-only");
            Jval options = command.get("options");
            require(options.asArray().size == 1, name + " takes only the target");
            Jval target = options.asArray().first();
            require(target.getString("name").equals("target") && target.getInt("type", -1) == 3
                    && target.getBool("required", false), name + " requires a string target");
            require(target.getString("description").contains("UUID"), name + " asks for UUID rather than a name");
        }
        require(names.equals(Set.of("ban", "unban", "free", "mute", "unmute")), "old and new Discord commands coexist");
    }

    static void checkDispatch() throws Exception {
        boolean[] onMain = {false};
        int[] posts = {0};
        Core.app = new MockApplication() {
            @Override public void post(Runnable action) {
                posts[0]++;
                onMain[0] = true;
                try { action.run(); } finally { onMain[0] = false; }
            }
        };
        List<String> calls = new ArrayList<>();
        DiscordModeration discord = new DiscordModeration(
                (target, actor, reason) -> { throw new AssertionError("mute dispatched as ban"); },
                (target, actor) -> { throw new AssertionError("mute dispatched as unban"); },
                (target, actor) -> { throw new AssertionError("mute dispatched as free"); },
                (target, actor) -> {
                    require(onMain[0], "mute callback runs on game thread");
                    calls.add("mute:" + target + ":" + actor);
                    return "Muted **Target** @everyone.";
                },
                (target, actor) -> {
                    require(onMain[0], "unmute callback runs on game thread");
                    calls.add("unmute:" + target + ":" + actor);
                    return "Unmuted Target.";
                });
        DiscordApi api = (DiscordApi) field(discord, "api");
        FakeHttp http = new FakeHttp();
        set(api, "client", http);
        set(api, "applicationId", "application");
        Config.discordCommandGuild = "guild";
        Config.discordCommandRole = "staff";
        run(discord, interaction("mute", "guild", "staff", false));
        run(discord, interaction("unmute", "guild", "staff", false));
        require(calls.equals(List.of("mute:player-uuid:Mod **Name** (Discord)",
                "unmute:player-uuid:Mod **Name** (Discord)")), "distinct actions preserve UUID and moderator attribution");
        require(posts[0] == 2, "permitted actions post to main thread once");
        require(http.bodies.size() == 4, "each interaction acknowledges and answers");
        Jval ack = Jval.read(http.bodies.get(0));
        require(ack.getInt("type", -1) == 5 && ack.get("data").getInt("flags", -1) == 64, "deferred acknowledgement is ephemeral");
        Jval answer = Jval.read(http.bodies.get(1));
        require(answer.getString("content").contains("\\*\\*Target\\*\\*"), "player markup in answer is escaped");
        require(answer.get("allowed_mentions").get("parse").asArray().size == 0, "reply suppresses mentions");
        for (String command : List.of("mute", "unmute")) {
            run(discord, interaction(command, "other-guild", "staff", true));
            run(discord, interaction(command, "guild", "other-role", false));
        }
        require(calls.size() == 2 && posts[0] == 2, "wrong guild and missing role never dispatch either action");
        require(Jval.read(http.bodies.get(http.bodies.size() - 1)).getString("content")
                .equals("You may not use this command."), "permission failure explains refusal");
        run(discord, interaction("mute", "guild", "", true));
        require(calls.size() == 3, "Discord administrator can use commands with a role configured");
        Config.discordCommandRole = "";
        run(discord, interaction("unmute", "guild", "staff", false));
        require(calls.size() == 3, "unset role requires Administrator rather than opening access");
        run(discord, interaction("unmute", "guild", "", true));
        require(calls.size() == 4, "Administrator works when no role configured");
    }

    static Jval interaction(String command, String guild, String role, boolean administrator) {
        return Jval.read("{\"id\":\"interaction\",\"token\":\"interaction-token\",\"guild_id\":\"" + guild
                + "\",\"type\":2,\"data\":{\"name\":\"" + command
                + "\",\"options\":[{\"name\":\"target\",\"value\":\" player-uuid \"}]},"
                + "\"member\":{\"roles\":[\"" + role + "\"],\"permissions\":\"" + (administrator ? "8" : "0")
                + "\",\"user\":{\"global_name\":\"Mod **Name**\",\"username\":\"mod\"}}}");
    }

    static void run(DiscordModeration discord, Jval payload) throws Exception {
        Class<?> interaction = Class.forName("Extinction.DiscordModeration$Interaction");
        Method parse = interaction.getDeclaredMethod("parse", Jval.class);
        parse.setAccessible(true);
        Method run = DiscordModeration.class.getDeclaredMethod("run", interaction);
        run.setAccessible(true);
        run.invoke(discord, parse.invoke(null, payload));
    }

    @SuppressWarnings("unchecked")
    static void checkLog() throws Exception {
        BanLog log = new BanLog();
        Deque<String> pending = (Deque<String>) field(log, "pending");
        log.logModeration("warned", "Target", "uuid", "Mod", "hub", "reason");
        require(pending.isEmpty(), "unconfigured log is harmless");
        Config.discordBanLogWebhookUrl = "https://discord.com/api/webhooks/test/test";
        log.start();
        log.logModeration("warned", "[red]**Target**\n@everyone", "uuid+/=", "Mod **Name** (Discord)",
                "port-6568", "[orange]Stop **that**\n@everyone");
        log.logModeration("muted", "Target", "uuid+/=", Bans.Origin.CONSOLE, "hub", "");
        log.logModeration("unmuted", "Target", "uuid+/=", "Mod", "hub", null);
        require(pending.size() == 3, "warn, mute and unmute queue separate log messages");
        Jval warning = Jval.read(pending.poll());
        String content = warning.getString("content");
        require(content.startsWith("Mod \\*\\*Name\\*\\* (Discord) warned [red]\\*\\*Target\\*\\* @everyone"),
                "moderator and target markup escaped while target colours retained");
        require(content.contains("with the **reason:** Stop \\*\\*that\\*\\* @everyone"), "reason colour stripped, markup escaped, newline flattened");
        require(content.contains("\n**Server:** port-6568\n**uuid:** `uuid+/=`"), "origin and UUID retained");
        require(content.split("\n").length == 3, "user-controlled newlines cannot inject log lines");
        require(warning.get("allowed_mentions").get("parse").asArray().size == 0, "log suppresses all mentions");
        String mute = Jval.read(pending.poll()).getString("content");
        String unmute = Jval.read(pending.poll()).getString("content");
        require(mute.startsWith("Console muted Target") && !mute.contains("reason:"), "console mute omits absent reason");
        require(unmute.startsWith("Mod unmuted Target") && !unmute.contains("reason:"), "unmute omits null reason");
    }

    static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    static final class FakeHttp extends HttpClient {
        final List<String> bodies = new ArrayList<>();
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new AssertionError("No asynchronous Discord requests expected");
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> push) { return sendAsync(request, handler); }
        @Override @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            StringBuilder body = new StringBuilder();
            request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
                @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                @Override public void onNext(ByteBuffer bytes) { body.append(StandardCharsets.UTF_8.decode(bytes)); }
                @Override public void onError(Throwable error) { throw new AssertionError(error); }
                @Override public void onComplete() { }
            });
            bodies.add(body.toString());
            return new HttpResponse<>() {
                @Override public int statusCode() { return 200; }
                @Override public HttpRequest request() { return request; }
                @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
                @Override public T body() { return (T) "{}"; }
                @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
                @Override public URI uri() { return request.uri(); }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
        }
    }
}
