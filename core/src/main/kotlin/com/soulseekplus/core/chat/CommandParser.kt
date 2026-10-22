package com.soulseekplus.core.chat

import com.soulseekplus.core.model.SearchScope

/** [CommandParser.parse] sonucu: ne yapılacağını anlatan saf veri. */
sealed interface ParsedCommand {
    data object Empty : ParsedCommand

    /** Düz mesaj ya da `//` ile kaçırılmış eğik çizgili metin. */
    data class Say(val text: String) : ParsedCommand

    /** `/me` eylemi (geçerli sohbete). */
    data class Me(val text: String) : ParsedCommand

    data class Join(val room: String) : ParsedCommand

    /** [room] null ise geçerli oda. */
    data class Leave(val room: String?) : ParsedCommand

    data class SayInRoom(val room: String, val text: String) : ParsedCommand

    data class Msg(val username: String, val text: String) : ParsedCommand

    data class OpenPm(val username: String) : ParsedCommand

    /** [username] null ise geçerli özel sohbet. */
    data class ClosePm(val username: String?) : ParsedCommand

    data class Browse(val username: String) : ParsedCommand

    data class ShowUserInfo(val username: String) : ParsedCommand

    data class Search(val query: String, val scope: SearchScope) : ParsedCommand

    data class UserList(val action: UserListAction) : ParsedCommand

    data object ToggleAway : ParsedCommand

    data object Back : ParsedCommand

    data object Clear : ParsedCommand

    data class SetAlias(val name: String, val expansion: String) : ParsedCommand

    data class RemoveAlias(val name: String) : ParsedCommand

    data object ListAliases : ParsedCommand

    data class Help(val query: String) : ParsedCommand

    data class InvalidUsage(val command: String, val usage: String) : ParsedCommand

    data class InvalidAlias(val name: String) : ParsedCommand

    data class Unknown(val name: String) : ParsedCommand
}

/** Ayrıştırma bağlamı: geçerli pencere, katılınmış odalar ve kullanıcı takma adları. */
data class CommandContext(
    val target: ChatTarget? = null,
    val joinedRooms: Collection<String> = emptyList(),
    val aliases: Map<String, String> = emptyMap(),
)

/**
 * Sohbet girdisi ayrıştırıcısı (Nicotine+ core_commands eklentisi komut kümesi). Saftır; hiçbir şey göndermez.
 *
 * Komutlar: /join /leave /say /pm /close /msg /me /browse /whois /info /search /rsearch /bsearch /usearch /ignore /unignore
 * /ban /unban /add /rem /away /back /clear /alias /unalias /help (+ kısa takma adlar) ve kullanıcı tanımlı takma adlar.
 * Kullanıcı takma adı gövdesinde `$1`..`$9` ve `$*` yer tutucuları kullanılabilir; yer tutucu yoksa argümanlar sona eklenir.
 */
object CommandParser {

    const val MAX_ALIAS_DEPTH = 4

    private class Spec(val name: String, val aliases: List<String>, val parameters: String, val group: CommandGroup)

    private val SPECS: List<Spec> = listOf(
        Spec("help", listOf("?"), "[query]", CommandGroup.ALIASES),
        Spec("away", listOf("a"), "", CommandGroup.CHAT),
        Spec("back", emptyList(), "", CommandGroup.CHAT),
        Spec("clear", listOf("cl"), "", CommandGroup.CHAT),
        Spec("me", emptyList(), "<something..>", CommandGroup.CHAT),
        Spec("join", listOf("j"), "<room>", CommandGroup.ROOMS),
        Spec("leave", listOf("l"), "[room]", CommandGroup.ROOMS),
        Spec("say", emptyList(), "<room> <message..>", CommandGroup.ROOMS),
        Spec("pm", emptyList(), "<user>", CommandGroup.PRIVATE),
        Spec("close", listOf("c"), "[user]", CommandGroup.PRIVATE),
        Spec("msg", listOf("m"), "<user> <message..>", CommandGroup.PRIVATE),
        Spec("add", listOf("buddy"), "[user]", CommandGroup.USERS),
        Spec("rem", listOf("unbuddy"), "[user]", CommandGroup.USERS),
        Spec("browse", listOf("b"), "[user]", CommandGroup.USERS),
        Spec("whois", listOf("info", "w"), "[user]", CommandGroup.USERS),
        Spec("ban", emptyList(), "[user]", CommandGroup.FILTERS),
        Spec("unban", emptyList(), "[user]", CommandGroup.FILTERS),
        Spec("ignore", emptyList(), "[user]", CommandGroup.FILTERS),
        Spec("unignore", emptyList(), "[user]", CommandGroup.FILTERS),
        Spec("search", listOf("s"), "<query>", CommandGroup.SEARCH),
        Spec("rsearch", listOf("rs"), "[room] <query>", CommandGroup.SEARCH),
        Spec("bsearch", listOf("bs"), "<query>", CommandGroup.SEARCH),
        Spec("usearch", listOf("us"), "<user> <query>", CommandGroup.SEARCH),
        Spec("alias", emptyList(), "[name] [command..]", CommandGroup.ALIASES),
        Spec("unalias", emptyList(), "<name>", CommandGroup.ALIASES),
    )

    /** Tüm yerleşik komutlar (yardım ekranı için). */
    val COMMANDS: List<CommandInfo> = SPECS.map { CommandInfo(it.name, it.aliases, it.parameters, it.group) }

    private val CANONICAL: Map<String, String> = buildMap {
        for (spec in SPECS) {
            put(spec.name, spec.name)
            for (alias in spec.aliases) put(alias, spec.name)
        }
    }

    private val USAGE: Map<String, String> = SPECS.associate { it.name to it.parameters }

    private val ALIAS_NAME = Regex("[a-z0-9_]{1,24}")

    fun isBuiltin(name: String): Boolean = name.lowercase() in CANONICAL

    /** Takma ad adı geçerli ve yerleşik komutlarla çakışmıyor mu. */
    fun isValidAliasName(name: String): Boolean = ALIAS_NAME.matches(name) && name !in CANONICAL

    fun help(query: String): List<CommandInfo> {
        val q = query.trim().lowercase().removePrefix("/")
        if (q.isEmpty()) return COMMANDS
        return COMMANDS.filter { info -> q in info.name || info.aliases.any { q in it } }
    }

    fun parse(input: String, context: CommandContext = CommandContext()): ParsedCommand = parse(input, context, 0)

    private fun parse(rawInput: String, context: CommandContext, depth: Int): ParsedCommand {
        val input = rawInput.trim()
        if (input.isEmpty()) return ParsedCommand.Empty
        if (!input.startsWith("/")) return ParsedCommand.Say(input)
        if (input.startsWith("//")) return ParsedCommand.Say(input.substring(1))

        val body = input.substring(1)
        val firstSpace = body.indexOfFirst { it.isWhitespace() }
        val word = (if (firstSpace < 0) body else body.substring(0, firstSpace)).lowercase()
        val args = if (firstSpace < 0) "" else body.substring(firstSpace).trim()
        if (word.isEmpty()) return ParsedCommand.Unknown("")

        val canonical = CANONICAL[word]
        if (canonical == null) {
            val expansion = context.aliases[word]
            if (expansion != null && depth < MAX_ALIAS_DEPTH) {
                return parse(expand(expansion, args), context, depth + 1)
            }
            return ParsedCommand.Unknown(word)
        }
        return dispatch(canonical, args, context)
    }

    private fun dispatch(command: String, args: String, context: CommandContext): ParsedCommand {
        val target = context.target
        val currentPrivateUser = (target as? ChatTarget.Private)?.username

        /** Kullanıcı argümanı: verilmişse ilk sözcük, yoksa açık özel sohbetin kullanıcısı. */
        fun userArg(): String? = args.split(WHITESPACE, limit = 2).firstOrNull()?.takeIf { it.isNotEmpty() } ?: currentPrivateUser
        fun usage() = ParsedCommand.InvalidUsage(command, USAGE[command].orEmpty())

        return when (command) {
            "help" -> ParsedCommand.Help(args)
            "away" -> ParsedCommand.ToggleAway
            "back" -> ParsedCommand.Back
            "clear" -> ParsedCommand.Clear
            "me" -> if (args.isEmpty()) usage() else ParsedCommand.Me(args)
            "join" -> if (args.isEmpty()) usage() else ParsedCommand.Join(args)
            "leave" -> ParsedCommand.Leave(args.ifEmpty { null })
            "say" -> {
                if (args.isEmpty()) return usage()
                val room = context.joinedRooms
                    .filter { args.length > it.length && args.startsWith(it, ignoreCase = true) && args[it.length].isWhitespace() }
                    .maxByOrNull { it.length }
                if (room != null) {
                    val text = args.substring(room.length).trim()
                    return if (text.isEmpty()) usage() else ParsedCommand.SayInRoom(room, text)
                }
                val parts = args.split(WHITESPACE, limit = 2)
                if (parts.size < 2 || parts[1].isBlank()) usage() else ParsedCommand.SayInRoom(parts[0], parts[1].trim())
            }
            "pm" -> userArg()?.let { ParsedCommand.OpenPm(it) } ?: usage()
            "close" -> ParsedCommand.ClosePm(args.ifEmpty { null })
            "msg" -> {
                val parts = args.split(WHITESPACE, limit = 2)
                if (parts.size < 2 || parts[0].isEmpty() || parts[1].isBlank()) usage() else ParsedCommand.Msg(parts[0], parts[1].trim())
            }
            "add" -> userArg()?.let { ParsedCommand.UserList(UserListAction.AddBuddy(it)) } ?: usage()
            "rem" -> userArg()?.let { ParsedCommand.UserList(UserListAction.RemoveBuddy(it)) } ?: usage()
            "ban" -> userArg()?.let { ParsedCommand.UserList(UserListAction.Ban(it)) } ?: usage()
            "unban" -> userArg()?.let { ParsedCommand.UserList(UserListAction.Unban(it)) } ?: usage()
            "ignore" -> userArg()?.let { ParsedCommand.UserList(UserListAction.Ignore(it)) } ?: usage()
            "unignore" -> userArg()?.let { ParsedCommand.UserList(UserListAction.Unignore(it)) } ?: usage()
            "browse" -> userArg()?.let { ParsedCommand.Browse(it) } ?: usage()
            "whois" -> userArg()?.let { ParsedCommand.ShowUserInfo(it) } ?: usage()
            "search" -> if (args.isEmpty()) usage() else ParsedCommand.Search(args, SearchScope.Network)
            "bsearch" -> if (args.isEmpty()) usage() else ParsedCommand.Search(args, SearchScope.Buddies)
            "usearch" -> {
                val parts = args.split(WHITESPACE, limit = 2)
                if (parts.size < 2 || parts[0].isEmpty() || parts[1].isBlank()) usage()
                else ParsedCommand.Search(parts[1].trim(), SearchScope.User(parts[0]))
            }
            "rsearch" -> {
                if (args.isEmpty()) return usage()
                // Oda penceresindeyse o oda; değilse ilk sözcük katılınmış bir oda adı olmalı.
                val inRoom = (target as? ChatTarget.Room)?.name
                val named = context.joinedRooms
                    .filter { args.length > it.length && args.startsWith(it, ignoreCase = true) && args[it.length].isWhitespace() }
                    .maxByOrNull { it.length }
                when {
                    named != null && args.substring(named.length).isNotBlank() ->
                        ParsedCommand.Search(args.substring(named.length).trim(), SearchScope.Room(named))
                    inRoom != null -> ParsedCommand.Search(args, SearchScope.Room(inRoom))
                    else -> usage()
                }
            }
            "alias" -> {
                if (args.isEmpty()) return ParsedCommand.ListAliases
                val parts = args.split(WHITESPACE, limit = 2)
                val name = parts[0].lowercase().removePrefix("/")
                if (!isValidAliasName(name)) return ParsedCommand.InvalidAlias(parts[0])
                if (parts.size < 2 || parts[1].isBlank()) usage() else ParsedCommand.SetAlias(name, parts[1].trim())
            }
            "unalias" -> if (args.isEmpty()) usage() else ParsedCommand.RemoveAlias(args.split(WHITESPACE)[0].lowercase().removePrefix("/"))
            else -> ParsedCommand.Unknown(command)
        }
    }

    /** `$1..$9`, `$*` yer tutucularını doldurur; yer tutucu yoksa argümanlar sona eklenir. */
    fun expand(expansion: String, args: String): String {
        val tokens = if (args.isEmpty()) emptyList() else args.split(WHITESPACE)
        var used = false
        val out = StringBuilder()
        var i = 0
        while (i < expansion.length) {
            val c = expansion[i]
            if (c == '$' && i + 1 < expansion.length) {
                val n = expansion[i + 1]
                if (n == '*') {
                    out.append(args)
                    used = true
                    i += 2
                    continue
                }
                if (n in '1'..'9') {
                    out.append(tokens.getOrElse(n - '1') { "" })
                    used = true
                    i += 2
                    continue
                }
            }
            out.append(c)
            i++
        }
        val result = out.toString().trim()
        return if (!used && args.isNotEmpty()) "$result $args" else result
    }

    private val WHITESPACE = Regex("\\s+")
}
