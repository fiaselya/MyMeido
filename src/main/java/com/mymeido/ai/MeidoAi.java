package com.mymeido.ai;

import com.mymeido.MeidoCompat;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mymeido.MeidoLocale;
import com.mymeido.MyMeido;
import com.mymeido.chat.MeidoChat;
import com.mymeido.entity.MeidoEntity;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * 对话路由层（2026-09-20 大改：mod 不再绑定模型，只有两种状态）。
 *
 * <h2>两种状态（{@link MeidoAiConfig#aiEnabled()}）</h2>
 * <ul>
 *   <li><b>基础模式</b>（没填 API）——<b>一句 AI 请求都不发</b>：
 *       右键打招呼走 mod 自带台词；玩家在聊天栏/输入框说的话<b>不回复</b>
 *       （她只是没接上 API，不是坏了 —— 状态提示与 aiguide 负责说明）；</li>
 *   <li><b>API 模式</b>（填了 {@code api_base_url} + {@code api_model}）——
 *       打招呼与玩家发言都交给这个 API 生成，失败回落自带台词兜底。</li>
 * </ul>
 *
 * <p><b>事件驱动（A4-3）</b>：只有正经交互（右键、对她说话）才发请求；
 * 种地 / 钓鱼 / 游走 / 走路完全不调。
 *
 * <p><b>失败一定不卡游戏</b>：请求全程异步；失败记 60 秒冷却 + 每玩家一次性说明 +
 * 兜底台词。基础模式下不回复是<b>设计</b>，不是失败。
 */
public final class MeidoAi {

    /** 同一个她两次 AI 对话的最小间隔（毫秒）。防连点右键刷请求。 */
    private static final long THROTTLE_MS = 3000;

    /** API 失败后静默多久（毫秒）。冷却期内不再尝试，直接走兜底。 */
    private static final long BACKEND_COOLDOWN_MS = 60_000;

    /** 每个她最多记多少条历史（含双方）。 */
    private static final int HISTORY_LIMIT = 12;

    /** API 最近一次失败的时间戳；0 = 没坏。 */
    private static volatile long apiDownAt = 0L;

    /** 同一个玩家 + 同一个原因只提示一次。 */
    private static final Map<String, Boolean> NOTIFIED = new ConcurrentHashMap<>();

    /** API 失败后兜底的短句（中英两版，运行时按语言选，避免类加载时把语言焊死）。 */
    private static final String[] CANNED_REPLIES_ZH = {
            "嗯，我听着呢。",
            "好的，主人。",
            "……你说什么？风好像把话吹跑了。",
            "我知道啦。",
            "嗯嗯。",
    };
    private static final String[] CANNED_REPLIES_EN = {
            "Yeah, I'm listening.",
            "Yes, master.",
            "...What did you say? The wind blew it away.",
            "I see.",
            "Mhm.",
    };

    /** 按当前语言挑一句兜底短句（运行时取值）。 */
    private static String cannedReply(MeidoEntity meido) {
        int i = meido.getRandom().nextInt(CANNED_REPLIES_ZH.length);
        return MeidoLocale.pick(CANNED_REPLIES_ZH[i], CANNED_REPLIES_EN[i]);
    }

    /**
     * 主动搭话<b>实在换不出新句子</b>时的兜底（API 两次都想重复）。
     *
     * <p>刻意写成「生活里的一句话」而不是应答 —— 她这时候是<b>先开口的人</b>，
     * 说「好的，主人」等于在回答一句根本没人说的话。
     * 挑句子时会避开最近说过的（见 {@link #pickUnsaidProactive}）。
     */
    private static final String[] CANNED_PROACTIVE_ZH = {
            "主人，我一直都在的。",
            "……你就这么站着，是在陪我吗？",
            "今天的风挺舒服的。",
            "主人要不要先歇一会儿？",
    };
    private static final String[] CANNED_PROACTIVE_EN = {
            "Master, I've been here the whole time.",
            "...Standing there like that... are you keeping me company?",
            "The breeze feels nice today.",
            "Master, would you like to rest for a bit?",
    };

    /** {@link #buildSystemPrompt} 的「这不是主动搭话」哨兵值。 */
    private static final int NOT_PROACTIVE = -1;

    private static final Map<UUID, Long> LAST_CALL = new ConcurrentHashMap<>();

    /**
     * ★ 对话串行化：一只她同时只允许一个在途请求。
     *
     * <p>LLM 回复是异步的，玩家连发两条时，第一条的回复会<b>晚于</b>第二条进历史 ——
     * 模型看到的上下文变成「user1, user2, 回答1」，整个对话就此错乱。
     */
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    /** 在途时新来的话只留<b>最新一句</b>。 */
    private static final Map<UUID, String> PENDING = new ConcurrentHashMap<>();

    /** 记忆压缩阈值：近期历史攒满这么多条就压一次摘要。 */
    private static final int SUMMARY_THRESHOLD = 12;

    /** 正在压缩摘要的她（压缩请求也占串行槽）。 */
    private static final Set<UUID> SUMMARIZING = ConcurrentHashMap.newKeySet();

    private MeidoAi() {
    }

    // ------------------------------------------------------------------
    // 入口（事件驱动的全部两个触发点）
    // ------------------------------------------------------------------

    /**
     * 玩家右键她打招呼。
     *
     * <p>基础模式下这也是唯一会让她说话的方式：直接用 mod 自带的问候语
     * （一期就有的那套，按好感度挑句子），一个网络请求都不发。
     */
    public static void onGreet(MeidoEntity meido, ServerPlayerEntity player) {
        if (!MeidoAiConfig.aiEnabled()) {
            MeidoChat.say(meido, MeidoChat.greeting(meido, player));
            return;
        }
        talk(meido, player, "", "（" + player.getName().getString() + " 走过来向你打招呼）");
    }

    /**
     * 玩家主动说话（原生聊天栏 T / G 输入框 / {@code /mymeido chat}）。
     *
     * <p>★ 基础模式：<b>故意不回复</b>（定稿 —— 没接 API 就不做高阶对话处理）。
     * 只写一行日志，方便排查「是不是没接上」。
     */
    public static void onPlayerLine(MeidoEntity meido, ServerPlayerEntity player, String text) {
        if (!MeidoAiConfig.aiEnabled()) {
            MyMeido.LOGGER.info("[mymeido][ai] No API connected, skipping reply to player's message (basic mode)");
            return;
        }
        talk(meido, player, text, null);
    }

    /**
     * ★ 她<b>主动</b>搭话（创建人在她附近待够了 → 她走过去先开口，2026-09-21 第四批）。
     *
     * <h2>和 {@link #onGreet} 的区别</h2>
     * <ul>
     *   <li>没有「主人刚说的那句话」—— 提示词改成「轮到你先开口」，并且
     *       <b>禁止重复</b>最近主动说过的（把最近几句原样喂回 prompt，见
     *       {@link MeidoEntity#recentProactiveSaid()}）；</li>
     *   <li>要按好感度决定说什么 —— 基础人设里本来就带着模糊的关系氛围描述
     *       （{@link MeidoEntity#favorHint()}），主动分支里再点明一次让它真被用上；</li>
     *   <li><b>不占</b>玩家侧 3 秒节流（那是防连点右键的），也不排队
     *       （她主动开口时正在聊天，这次就不说了，下次再说）。</li>
     * </ul>
     *
     * <p><b>失败一律安静收场</b>：后端冷却中、正在对话、请求失败 —— 都只是不开口，
     * 不往聊天栏里塞兜底短句。她特地走过来却只憋出一句「好的，主人」，
     * 比这次不搭话更破坏观感。
     *
     * <p>时间 / 次数 / 白天黑夜 / 走过去这些判断全在 {@link MeidoEntity} 那边，
     * 这里只管「开口说什么」。
     */
    public static void onProactive(MeidoEntity meido, ServerPlayerEntity player) {
        if (!MeidoAiConfig.aiEnabled()) {
            MyMeido.LOGGER.info("[mymeido][ai] No API connected, skipping proactive chat (basic mode)");
            return;
        }
        if (apiCooling()) {
            MyMeido.LOGGER.info("[mymeido][ai] API cooling down, skipping this proactive chat");
            return;
        }
        if (!IN_FLIGHT.add(meido.getUuid())) {
            MyMeido.LOGGER.info("[mymeido][ai] Already in a conversation, skipping this proactive chat");
            return;
        }
        dispatchProactive(meido, player, 0);
    }

    /**
     * 真的发一次「主动搭话」请求。{@code attempt} 0 = 第一次，1 = 上一句重复了、换一个。
     * <b>IN_FLIGHT 已占位</b>，两条出口都走 {@link #endTurn}。
     */
    private static void dispatchProactive(MeidoEntity meido, ServerPlayerEntity player, int attempt) {
        List<MeidoLlm.Msg> messages = new ArrayList<>();
        messages.add(new MeidoLlm.Msg("system", buildSystemPrompt(meido, null, attempt)));
        Deque<String[]> history = meido.aiHistory();
        synchronized (history) {
            for (String[] entry : history) {
                messages.add(new MeidoLlm.Msg(entry[0], entry[1]));
            }
        }
        MeidoLlm.chat(MeidoCompat.serverOf(meido), MeidoAiConfig.llmOptions(), messages,
                reply -> finishProactive(meido, player, reply, attempt),
                error -> {
                    apiDownAt = System.currentTimeMillis();
                    MyMeido.LOGGER.warn("[mymeido][ai] Proactive chat failed ({}), staying quiet this time", error);
                    notifyFallReason(player);
                    endTurn(meido, player);
                });
    }

    /** 收到主动搭话的回复：判重 → 换一句 → 记住 → 广播。 */
    private static void finishProactive(MeidoEntity meido, ServerPlayerEntity player, String reply, int attempt) {
        String repeated = repeatedProactiveLine(meido, reply);
        if (repeated != null && attempt == 0) {
            // 第一次撞车：让她带着「上一句重复了」的提示重想一遍（只重试这一次）。
            MyMeido.LOGGER.warn("[mymeido][ai] Proactive line collided with something said before ({}), asking her to pick another", repeated);
            dispatchProactive(meido, player, 1);
            return;   // ★ 不能调 endTurn：IN_FLIGHT 要一直占着，否则这轮的槽位会提前腾出来
        }
        String line = reply;
        if (repeated != null) {
            line = pickUnsaidProactive(meido);
            MyMeido.LOGGER.warn("[mymeido][ai] Still a repeat after switching, falling back to built-in line: {}", line);
        }
        meido.rememberProactiveSaid(line);
        recordHistory(meido, "assistant", line);
        MyMeido.LOGGER.info("[mymeido][ai] {} (proactive): {}", meido.characterName(), line);
        MeidoChat.say(meido, line);
        // ★ 刻意不调 schedulePersonaUpdate：这一轮玩家一句话都没说，
        //   拿她自己的话去改人设卡只会让人设越跑越偏，还白花一次请求。
        endTurn(meido, player);
    }

    /** 这句是不是最近主动说过的（判据与复读拦截同一套：去空白、去尾标点）。撞上则返回那一句。 */
    private static String repeatedProactiveLine(MeidoEntity meido, String reply) {
        if (reply == null || reply.isBlank()) {
            return "(empty reply)";
        }
        String target = normalize(reply);
        for (String old : meido.recentProactiveSaid()) {
            if (normalize(old).equals(target)) {
                return old;
            }
        }
        return null;
    }

    /** 从自带台词里挑一句最近没说过的（全说过了就随便挑一句，不递归）。按当前语言取值。 */
    private static String pickUnsaidProactive(MeidoEntity meido) {
        List<String> said = meido.recentProactiveSaid();
        for (int i = 0; i < CANNED_PROACTIVE_ZH.length; i++) {
            String line = MeidoLocale.pick(CANNED_PROACTIVE_ZH[i], CANNED_PROACTIVE_EN[i]);
            if (!said.contains(line)) {
                return line;
            }
        }
        int i = meido.getRandom().nextInt(CANNED_PROACTIVE_ZH.length);
        return MeidoLocale.pick(CANNED_PROACTIVE_ZH[i], CANNED_PROACTIVE_EN[i]);
    }

    private static void talk(MeidoEntity meido, ServerPlayerEntity player, String playerText, String systemEvent) {
        // ---- 限流：同一个她 3 秒内只认一次 AI 请求 ----
        // ★ 先查再记：不能把时间戳无条件写回去，否则连点右键会不停续期。
        if (player != null) {
            long now = System.currentTimeMillis();
            Long last = LAST_CALL.get(meido.getUuid());
            if (last != null && now - last < THROTTLE_MS) {
                return;
            }
            LAST_CALL.put(meido.getUuid(), now);
        }

        // ---- 对话串行化：在途时只留最新一句，等当前回复完再问 ----
        UUID id = meido.getUuid();
        if (!IN_FLIGHT.add(id)) {
            if (!playerText.isBlank()) {
                PENDING.put(id, playerText);
            }
            return;
        }

        if (!playerText.isBlank()) {
            recordHistory(meido, "user", playerText);
        }

        dispatch(meido, player, systemEvent);
    }

    /** IN_FLIGHT 已占位的前提下真正发请求。同步失败的路径必须 {@link #endTurn} 收尾。 */
    private static void dispatch(MeidoEntity meido, ServerPlayerEntity player, String systemEvent) {
        // ---- 只有一种后端，冷却判断也就一条 ----
        if (apiCooling()) {
            fallback(meido, player, MeidoLocale.pick("API 连不上，先歇一会儿", "API unreachable, taking a short break"));
            endTurn(meido, player);
            return;
        }

        // ---- 拼 prompt：人设 + 记忆 + 状态 + 历史 ----
        List<MeidoLlm.Msg> messages = new ArrayList<>();
        messages.add(new MeidoLlm.Msg("system", buildSystemPrompt(meido, systemEvent)));
        Deque<String[]> history = meido.aiHistory();
        synchronized (history) {
            for (String[] entry : history) {
                messages.add(new MeidoLlm.Msg(entry[0], entry[1]));
            }
        }

        MeidoLlm.chat(MeidoCompat.serverOf(meido), MeidoAiConfig.llmOptions(), messages,
                reply -> {
                    // ★ 复读拦截：小模型偶尔把主人的原话原样吐回来 —— 换兜底短句顶上。
                    String lastUser = lastUserLine(meido);
                    if (isEcho(reply, lastUser)) {
                        MyMeido.LOGGER.warn("[mymeido][ai] Echo blocked: {} (original: {})", reply, lastUser);
                        String canned = cannedReply(meido);
                        recordHistory(meido, "assistant", canned);
                        MeidoChat.say(meido, canned);
                    } else {
                        recordHistory(meido, "assistant", reply);
                        MyMeido.LOGGER.info("[mymeido][ai] {}：{}", meido.characterName(), reply);
                        MeidoChat.say(meido, reply);
                    }
                    // 这一轮产生新信息了 → 让 API 顺手把人设卡更新一遍（异步、不挡对话）
                    schedulePersonaUpdate(meido);
                    endTurn(meido, player);
                },
                error -> {
                    // 失败：记冷却 + 一次性说明原因 + 兜底台词。她必须说话，游戏必须不卡。
                    apiDownAt = System.currentTimeMillis();
                    MyMeido.LOGGER.warn("[mymeido][ai] API failed ({}), using fallback line", error);
                    notifyFallReason(player);
                    fallback(meido, player, null);
                    endTurn(meido, player);
                });
    }

    /**
     * 一轮对话收尾：腾出串行槽位；排队里有新话就接着问（回复严格按提问顺序落历史）。
     * 历史攒满一轮先压摘要再处理排队的话。
     */
    private static void endTurn(MeidoEntity meido, ServerPlayerEntity player) {
        UUID id = meido.getUuid();
        IN_FLIGHT.remove(id);
        String next = PENDING.remove(id);

        boolean historyFull;
        synchronized (meido.aiHistory()) {
            historyFull = meido.aiHistory().size() >= SUMMARY_THRESHOLD;
        }
        // API 冷却时不压（压了也必失败白折腾），历史留着下轮再压
        if (historyFull && !apiCooling() && SUMMARIZING.add(id)) {
            // 摘要请求占用串行槽（压缩完才轮到排队的问话）
            if (IN_FLIGHT.add(id)) {
                doSummary(meido, player, next);
                return;
            }
            SUMMARIZING.remove(id);
        }
        if (next != null && !next.isBlank()) {
            recordHistory(meido, "user", next);
            dispatch(meido, player, null);
            return;
        }
        // 这一轮彻底结束了 → 把「记忆要点 + 最近对话」镜像一份成玩家能读的 Markdown。
        MeidoMemoryExport.write(meido);
    }

    /** API 还在 60 秒冷却期内 = true。 */
    private static boolean apiCooling() {
        return apiDownAt > 0 && System.currentTimeMillis() - apiDownAt < BACKEND_COOLDOWN_MS;
    }

    /**
     * 把近期历史压成记忆要点（存实体 NBT，下一轮注入 system prompt）。
     * 失败不丢数据 —— 历史原样放回，下轮再试。
     */
    private static void doSummary(MeidoEntity meido, ServerPlayerEntity player, String queuedLine) {
        List<MeidoLlm.Msg> snapshot = new ArrayList<>();
        synchronized (meido.aiHistory()) {
            for (String[] entry : meido.aiHistory()) {
                snapshot.add(new MeidoLlm.Msg(entry[0], entry[1]));
            }
            meido.aiHistory().clear();
        }

        List<MeidoLlm.Msg> messages = new ArrayList<>();
        messages.add(new MeidoLlm.Msg("system",
                "你在为一位 Minecraft 女仆整理对话记忆。把下面的对话压缩成要点，"
                        + "必须保留：主人下过的长期要求（说话加前后缀之类）、约定、重要的名字和数字。"
                        + "用第三人称，150 字以内，直接输出要点，不要客套。"));
        String old = meido.getAiSummary();
        if (!old.isEmpty()) {
            messages.add(new MeidoLlm.Msg("user", "旧的记忆要点：\n" + old));
            messages.add(new MeidoLlm.Msg("assistant", "好的，我会把新对话并进去。"));
        }
        messages.add(new MeidoLlm.Msg("user", "要压缩的对话：\n"
                + String.join("\n", snapshot.stream().map(m -> m.role() + "：" + m.content()).toList())));

        MeidoLlm.chat(MeidoCompat.serverOf(meido), MeidoAiConfig.llmOptions(), messages,
                summary -> {
                    meido.setAiSummary(summary);
                    MyMeido.LOGGER.info("[mymeido][ai] Memory compressed ({}): {}", meido.characterName(), summary);
                    finishSummary(meido, player, queuedLine);
                },
                error -> {
                    MyMeido.LOGGER.warn("[mymeido][ai] Memory compression failed ({}), history restored, retry next round", error);
                    synchronized (meido.aiHistory()) {
                        for (MeidoLlm.Msg message : snapshot) {
                            recordHistory(meido, message.role(), message.content());
                        }
                    }
                    finishSummary(meido, player, queuedLine);
                });
    }

    private static void finishSummary(MeidoEntity meido, ServerPlayerEntity player, String queuedLine) {
        UUID id = meido.getUuid();
        SUMMARIZING.remove(id);
        IN_FLIGHT.remove(id);
        // 压缩改写了「长期记忆」—— 玩家那份可读副本立刻跟上
        MeidoMemoryExport.write(meido);
        if (queuedLine != null && !queuedLine.isBlank()) {
            if (IN_FLIGHT.add(id)) {
                recordHistory(meido, "user", queuedLine);
                dispatch(meido, player, null);
                return;
            }
            PENDING.put(id, queuedLine);
        }
    }

    /** 历史里最近一条玩家台词；没有则 null。 */
    private static String lastUserLine(MeidoEntity meido) {
        Deque<String[]> history = meido.aiHistory();
        synchronized (history) {
            String last = null;
            for (String[] entry : history) {
                if ("user".equals(entry[0])) {
                    last = entry[1];
                }
            }
            return last;
        }
    }

    /** 复读判定：去掉空白和句尾标点后完全一致（大小写不敏感）。 */
    private static boolean isEcho(String reply, String lastUser) {
        if (lastUser == null || reply == null) {
            return false;
        }
        return normalize(reply).equals(normalize(lastUser));
    }

    private static String normalize(String text) {
        String out = text == null ? "" : text.strip().toLowerCase();
        out = out.replaceAll("[\\s。！？!?~，,．.]+$", "");
        return out.replaceAll("[\\s]", "");
    }

    // ------------------------------------------------------------------
    // 兜底与提示
    // ------------------------------------------------------------------

    /**
     * API 不可用时的兜底。打招呼回落成一期那套问候语；搭话回落成短应答。
     * {@code reason} 非空时会先在聊天栏补一句灰字说明。
     */
    private static void fallback(MeidoEntity meido, ServerPlayerEntity player, String reason) {
        if (reason != null && player != null) {
            player.sendMessage(Text.literal(MeidoLocale.pick("（" + reason + "，她暂时只能简单应付几句）",
                    "(" + reason + ", she can only manage a few words for now)")), false);
        }
        if (playerTextBlank(meido)) {
            MeidoChat.say(meido, MeidoChat.greeting(meido, player));
        } else {
            MeidoChat.say(meido, cannedReply(meido));
        }
    }

    /** 她最近的对话是不是一句玩家台词都没有（= 纯打招呼场景，兜底用问候语更像）。 */
    private static boolean playerTextBlank(MeidoEntity meido) {
        Deque<String[]> history = meido.aiHistory();
        synchronized (history) {
            return history.isEmpty() || !"user".equals(history.getLast()[0]);
        }
    }

    /** 同一个玩家只提示一次失败原因。 */
    private static void notifyFallReason(ServerPlayerEntity player) {
        String reason = MeidoLocale.pick("API 没有响应（地址/密钥/模型名对吗？敲 /mymeido aistatus 看看）",
                "API not responding (check the address/key/model name? run /mymeido aistatus)");
        if (player == null) {
            return;
        }
        String key = player.getUuid() + "|" + reason;
        if (NOTIFIED.putIfAbsent(key, Boolean.TRUE) == null) {
            player.sendMessage(Text.literal("[mymeido] " + reason), false);
        }
    }

    // ------------------------------------------------------------------
    // prompt 与历史
    // ------------------------------------------------------------------

    /**
     * 人设 prompt。写得短 —— 小模型吃长 system prompt 又慢又容易跑偏。
     * 好感度只给 {@link MeidoEntity#favorHint()} 的模糊描述，不泄露数值（R12 定稿）。
     */
    private static String buildSystemPrompt(MeidoEntity meido, String systemEvent) {
        return buildSystemPrompt(meido, systemEvent, NOT_PROACTIVE);
    }

    /**
     * 人设 prompt（{@code proactiveAttempt} = {@link #NOT_PROACTIVE} 时是普通一问一答）。
     *
     * @param proactiveAttempt ≥0 表示<b>她主动开口</b>：0 = 第一次想，1 = 上一句撞车了、让她换一句。
     */
    private static String buildSystemPrompt(MeidoEntity meido, String systemEvent, int proactiveAttempt) {
        boolean proactive = proactiveAttempt >= 0;
        StringBuilder sb = new StringBuilder();
        sb.append("你是 Minecraft 服务器里的女仆「").append(meido.characterName()).append("」。")
                .append("用中文说话，语气自然，回复一两句话、60个字以内，")
                .append("不要加引号，不要旁白，不要自我介绍，不要列清单。");
        if (proactive) {
            // ★ 主动开口时<b>没有</b>「主人刚说的那句」可接 —— 留着那条禁复述指令，
            //   只会把她推向历史里最后一条（往往是上一轮的对话），结果就是重复。
            sb.append("历史里主人下过的长期要求（比如说话加前后缀）要继续遵守。");
        } else {
            // 2026-09-20 实测：小模型爱把主人的原话复述/改写一遍当回复，明令禁止。
            sb.append("★不要复述或改写主人刚说过的话，直接回答最新的那句。")
                    .append("历史里主人下过的长期要求（比如说话加前后缀）要继续遵守。");
        }
        // ★ 记忆摘要必须注回 prompt：模型只看得见近期历史环形队列，
        //   压好的要点不进来 = 压缩白做（绯色实测「10 轮以前全忘」的根因）。
        String summary = meido.getAiSummary();
        if (!summary.isEmpty()) {
            sb.append("很久以前你们聊过的事（你的记忆，当作亲身经历，不要念出来源）：")
                    .append(summary).append("。");
        }
        // 人设卡：config/mymeido/personas/<skinId>.txt，玩家自由写；空卡跳过。
        String persona = MeidoPersona.personaFor(meido.getSkin().getId());
        if (!persona.isEmpty()) {
            sb.append("你的性格设定：").append(persona).append("。");
        }
        sb.append("你此刻的状态：").append(meido.getMission().displayName()).append("。")
                .append("你与主人的关系氛围：").append(meido.favorHint()).append("。");
        if (proactive) {
            sb.append("现在轮到你主动开口：主人已经在你身边待了一会儿，你想先跟他说句话。")
                    .append("★换一个你们最近没聊过的话题，不要重复你以前说过的话。");
            List<String> said = meido.recentProactiveSaid();
            if (!said.isEmpty()) {
                sb.append("你最近主动说过：").append(String.join("／", said))
                        .append("。这次必须换一个不一样的说法。");
            }
            if (proactiveAttempt > 0) {
                sb.append("★上一句你想的跟以前说过的撞上了，主人已经听过，务必换一个角度。");
            }
            // 「按好感度来想说什么」：基础人设里已经给了模糊的关系氛围描述，
            // 这里再点一次，否则小模型会当没看见那半句。
            sb.append("按你们现在的关系氛围决定说什么、怎么说：")
                    .append("关系亲近就主动聊聊你惦记他的事，关系一般就别太黏人、客客气气的。");
        } else if (systemEvent != null) {
            sb.append("刚刚发生：").append(systemEvent).append("。请自然地回应这个招呼。");
        }
        return sb.toString();
    }

    /** 历史进实体身上的环形队列（超限丢最老的）。随 NBT 存档持久化。 */
    private static void recordHistory(MeidoEntity meido, String role, String content) {
        Deque<String[]> history = meido.aiHistory();
        synchronized (history) {
            history.addLast(new String[] { role, content });
            while (history.size() > HISTORY_LIMIT) {
                history.removeFirst();
            }
        }
    }

    // ------------------------------------------------------------------
    // /mymeido aistatus 用
    // ------------------------------------------------------------------

    /** 一行人能读的状态。密钥绝不回显 —— 只给「已填/未填」。 */
    public static List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        if (!MeidoAiConfig.aiEnabled()) {
            lines.add(MeidoLocale.pick("状态：基础模式 —— 没接 API", "Status: Basic mode — no API connected"));
            lines.add(MeidoLocale.pick("她会用自带台词回应你的点击（右键），但不会回复你打的话",
                    "She answers your clicks (right-click) with built-in lines, but won't reply to what you type"));
            lines.add(MeidoLocale.pick("接 API：填 " + MeidoAiConfig.configFile() + " 里的 api_base_url 与 api_model，",
                    "To connect an API: set api_base_url and api_model in " + MeidoAiConfig.configFile() + ","));
            lines.add(MeidoLocale.pick("       再敲 /mymeido aireload（详细步骤看 /mymeido aiguide）",
                    "       then run /mymeido aireload (see /mymeido aiguide for details)"));
        } else {
            lines.add(MeidoLocale.pick("状态：API 模式", "Status: API mode"));
            lines.add(MeidoLocale.pick("接口：", "Endpoint: ") + MeidoAiConfig.baseUrl());
            lines.add(MeidoLocale.pick("模型：", "Model: ") + MeidoAiConfig.model());
            lines.add(MeidoLocale.pick("密钥：", "API key: ") + (MeidoAiConfig.apiKey().isBlank()
                    ? MeidoLocale.pick("未填（本地服务通常不用填）", "not set (local server usually needs none)")
                    : MeidoLocale.pick("已填", "set")));
            lines.add(MeidoLocale.pick("超时：", "Timeout: ") + MeidoAiConfig.timeoutMs() + " ms");
            lines.add(MeidoLocale.pick("流式：", "Streaming: ") + (MeidoAiConfig.stream()
                    ? MeidoLocale.pick("开（SSE，给对方逐片回）", "on (SSE, streamed back piece by piece)")
                    : MeidoLocale.pick("关（一次性返回；对方报「非流式不支持」时改成 api_stream=true）",
                            "off (returned all at once; set api_stream=true if it says streaming unsupported)")));
            int headerCount = MeidoAiConfig.extraHeaders().size();
            lines.add(MeidoLocale.pick("自定义头：", "Custom headers: ") + (headerCount == 0
                    ? MeidoLocale.pick("无", "none")
                    : headerCount + MeidoLocale.pick(" 条", " headers")));
            lines.add(MeidoLocale.pick("请求地址：", "Request URL: ") + MeidoLlm.endpoint(MeidoAiConfig.baseUrl()));
            if (apiCooling()) {
                lines.add(MeidoLocale.pick("⚠ 刚失败过，冷却中（约 1 分钟后自动恢复）",
                        "⚠ Recently failed, cooling down (auto-recovers in ~1 min)"));
            }
        }
        lines.add(MeidoLocale.pick("聊天样式：", "Chat style: ") + (MeidoAiConfig.plainChat()
                ? MeidoLocale.pick("纯白", "plain white")
                : MeidoLocale.pick("角色专属色", "character color")));
        lines.add(MeidoLocale.pick("人设自动更新：", "Auto persona update: ") + (MeidoAiConfig.autoPersonaUpdate()
                ? MeidoLocale.pick("开（每轮对话后按对话更新人设卡）", "on (updates persona card from chat each round)")
                : MeidoLocale.pick("关（可用 /mymeido persona extract 手动更新）",
                        "off (manual update via /mymeido persona extract)")));
        lines.add(MeidoLocale.pick("主动搭话：", "Proactive chat: ") + (MeidoAiConfig.proactiveEnabled()
                ? MeidoLocale.pick("开（创建人在 " + (int) MeidoEntity.PROACTIVE_RANGE + " 格内待够 "
                        + MeidoAiConfig.proactiveIntervalSeconds() + " 秒 → 白天她会走过来开口，"
                        + "每小时最多 " + MeidoAiConfig.proactiveMaxPerHour() + " 次）",
                        "on (creator stays within " + (int) MeidoEntity.PROACTIVE_RANGE + " blocks for "
                        + MeidoAiConfig.proactiveIntervalSeconds() + "s -> she walks over and speaks in daytime, up to "
                        + MeidoAiConfig.proactiveMaxPerHour() + " times/hour)")
                : MeidoAiConfig.proactiveChat()
                        ? MeidoLocale.pick("关（填了 API 才会开 —— 台词要让 API 现想）",
                                "off (only opens with an API set — lines are thought up live by the API)")
                        : MeidoLocale.pick("关（配置里 proactive_chat=false）", "off (proactive_chat=false in config)")));
        return lines;
    }

    /** 清失败冷却（aireload 顺手做，免得改完配置还要等一分钟）。 */
    public static void clearCooldowns() {
        apiDownAt = 0;
        NOTIFIED.clear();
        LAST_CALL.clear();
        // 排队里的东西清掉可以，但在途请求的槽位不能抢 —— 回调回来 endTurn 会自己摘。
        PENDING.clear();
    }

    // ------------------------------------------------------------------
    // 人设：手动提取（/mymeido persona extract）—— 与每轮自动更新共用同一条实现
    // ------------------------------------------------------------------

    /**
     * 手动触发一次人设提取（会告诉玩家结果）。
     *
     * <p>实际干活的是 {@link #runPersonaUpdate} —— 跟每轮对话后的自动更新同一套逻辑，
     * 只有「要不要报给玩家」不同，避免两处 prompt 与写法漂移。
     * 没接 API / 还没聊过 / 正在跑，都会明确说原因。
     */
    public static void extractPersona(MeidoEntity meido, ServerPlayerEntity player) {
        if (!MeidoAiConfig.aiEnabled()) {
            tell(player, MeidoLocale.pick("还没接 API，提取不了人设 —— 先按 /mymeido aiguide 把 api_base_url 与 api_model 填好",
                    "No API connected yet, can't extract persona — fill api_base_url and api_model first (see /mymeido aiguide)"));
            return;
        }
        if (apiCooling()) {
            tell(player, MeidoLocale.pick("API 刚失败过还在冷却，等一分钟再试",
                    "API just failed and is cooling down, wait a minute and retry"));
            return;
        }
        synchronized (meido.aiHistory()) {
            if (meido.aiHistory().isEmpty()) {
                tell(player, MeidoLocale.pick(meido.characterName() + " 还没跟你聊过天，没有素材可以提取 —— 先跟她说几句",
                        meido.characterName() + " hasn't chatted with you yet, nothing to extract — talk to her first"));
                return;
            }
        }
        UUID id = meido.getUuid();
        if (!PERSONA_RUNNING.add(id)) {
            PERSONA_DIRTY.add(id);
            tell(player, MeidoLocale.pick("已经有一次人设更新在跑了，等它写完会自动再补一次",
                    "A persona update is already running, it'll auto-run one more when done"));
            return;
        }
        tell(player, MeidoLocale.pick("正在让 API 读你们的对话、给她写人设……（1~5 秒）",
                "Asking the API to read your chat and write her persona... (1-5s)"));
        runPersonaUpdate(meido, player, true);
    }

    /** 给玩家说一句（控制台调用时只有日志）。 */
    private static void tell(ServerPlayerEntity player, String message) {
        MyMeido.LOGGER.info("[mymeido] {}", message);
        if (player != null) {
            player.sendMessage(Text.literal("[mymeido] " + message), false);
        }
    }

    // ------------------------------------------------------------------
    // 每轮对话后自动更新人设卡（2026-09-20 绯色要求）
    // ------------------------------------------------------------------

    /** 正在跑「人设更新」请求的她。 */
    private static final Set<UUID> PERSONA_RUNNING = ConcurrentHashMap.newKeySet();

    /** 跑的过程中又聊了新内容 → 记一笔，跑完再补一次（不堆请求）。 */
    private static final Set<UUID> PERSONA_DIRTY = ConcurrentHashMap.newKeySet();

    /**
     * 每轮对话结束后调用：让 API 把「这轮体现出来的新信息」并进人设卡。
     *
     * <p><b>为什么这么写</b>：
     * <ul>
     *   <li><b>合并去重</b>：同一只她同时只跑一个更新请求；跑的过程中又聊了，
     *       只记一个「脏」标记，请求回来再补跑一次 —— 玩家连发十句也只多花一次钱；</li>
     *   <li><b>不挡对话</b>：更新请求跟对话请求各走各的，聊天不用等它；</li>
     *   <li><b>失败静默</b>：人设更新失败不影响聊天，也不占后端冷却（下次对话会再试）。</li>
     * </ul>
     * 想关掉：{@code persona_auto_update=false}（云 API 按次计费时值得关）。
     */
    private static void schedulePersonaUpdate(MeidoEntity meido) {
        if (!MeidoAiConfig.autoPersonaUpdate() || !MeidoAiConfig.aiEnabled() || apiCooling()) {
            return;
        }
        int size;
        synchronized (meido.aiHistory()) {
            size = meido.aiHistory().size();
        }
        if (size < 2) {
            return;   // 只有一句「你好」这种，没东西可学
        }
        UUID id = meido.getUuid();
        if (!PERSONA_RUNNING.add(id)) {
            PERSONA_DIRTY.add(id);   // 已经在跑 → 记下再补一次，别叠请求
            return;
        }
        runPersonaUpdate(meido, null, false);
    }

    /** 收尾（成功/失败都走这里）：腾出名额；期间聊过就补跑一次。 */
    private static void finishPersonaUpdate(MeidoEntity meido) {
        UUID id = meido.getUuid();
        PERSONA_RUNNING.remove(id);
        if (PERSONA_DIRTY.remove(id)) {
            schedulePersonaUpdate(meido);
        }
    }

    /**
     * 真正跑一次人设更新。
     *
     * @param announce 是否把结果告诉玩家（手动 {@code /mymeido persona extract} 时 true，
     *                 每轮自动更新时 false —— 否则聊天栏每轮都被刷一条）
     */
    private static void runPersonaUpdate(MeidoEntity meido, ServerPlayerEntity player, boolean announce) {
        List<MeidoLlm.Msg> messages = new ArrayList<>();
        messages.add(new MeidoLlm.Msg("system",
                "你在维护一位 Minecraft 女仆的「人物设定卡」。根据下面的最新对话，输出更新后的完整设定卡。"
                        + "规则：① 保留原设定里没被推翻的内容；② 只补充对话中确实体现出来的新信息"
                        + "（性格、说话习惯/口癖、怎么称呼玩家、喜好与雷区）；"
                        + "③ 不要编造对话里没有的东西，不要写剧情经过；"
                        + "④ 第三人称、分点、200 字以内，直接输出卡片内容，不要客套话、不要标题。"));

        String summary = meido.getAiSummary();
        if (!summary.isEmpty()) {
            messages.add(new MeidoLlm.Msg("user", "已知的长期记忆要点：\n" + summary));
        }
        String current = MeidoPersona.personaFor(meido.getSkin().getId());
        if (!current.isEmpty()) {
            messages.add(new MeidoLlm.Msg("user", "现有设定卡（要在此基础上更新）：\n" + current));
        } else {
            messages.add(new MeidoLlm.Msg("user", "现有设定卡：还没有内容，这是第一次写。"));
        }
        StringBuilder transcript = new StringBuilder();
        Deque<String[]> history = meido.aiHistory();
        synchronized (history) {
            for (String[] entry : history) {
                transcript.append("user".equals(entry[0]) ? "主人：" : meido.characterName() + "：")
                        .append(entry[1]).append('\n');
            }
        }
        messages.add(new MeidoLlm.Msg("user", "最近的对话记录：\n" + transcript));

        MeidoLlm.chat(MeidoCompat.serverOf(meido), MeidoAiConfig.llmOptions(), messages,
                text -> {
                    java.nio.file.Path file = MeidoPersona.writeAuto(meido.getSkin().getId(), text);
                    if (file != null) {
                        MyMeido.LOGGER.info("[mymeido][ai] Persona card updated from conversation ({}): {}",
                                meido.characterName(), oneLine(text));
                        if (announce) {
                            tell(null, "Persona written to " + file + " (takes effect next conversation)");
                        }
                    } else if (announce) {
                        tell(null, "Failed to write persona file, check server log");
                    }
                    finishPersonaUpdate(meido);
                },
                error -> {
                    // 静默失败：不记后端冷却（人设是锦上添花，别连累正经对话）
                    MyMeido.LOGGER.warn("[mymeido][ai] Persona card update failed ({}), skipping this time", error);
                    if (announce) {
                        tell(null, "Persona extraction failed: " + error);
                    }
                    finishPersonaUpdate(meido);
                });
    }

    /** 日志里只留一行（人设是多行文本）。 */
    private static String oneLine(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() > 80 ? flat.substring(0, 80) + "…" : flat;
    }
}
