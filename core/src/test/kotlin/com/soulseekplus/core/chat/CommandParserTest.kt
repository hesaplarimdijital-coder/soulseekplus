package com.soulseekplus.core.chat

import com.soulseekplus.core.model.SearchScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private val inRoom = CommandContext(ChatTarget.Room("Lobby"), listOf("Lobby", "The Cave"))
    private val inPm = CommandContext(ChatTarget.Private("bob"))

    private fun parse(text: String, ctx: CommandContext = CommandContext()) = CommandParser.parse(text, ctx)

    @Test
    fun plainTextAndEscapes() {
        assertEquals(ParsedCommand.Empty, parse("   "))
        assertEquals(ParsedCommand.Say("hello there"), parse("  hello there "))
        assertEquals(ParsedCommand.Say("/not a command"), parse("//not a command"))
    }

    @Test
    fun joinLeaveSay() {
        assertEquals(ParsedCommand.Join("The Cave"), parse("/join The Cave"))
        assertEquals(ParsedCommand.Join("Lobby"), parse("/J Lobby"))
        assertEquals(ParsedCommand.InvalidUsage("join", "<room>"), parse("/join"))
        assertEquals(ParsedCommand.Leave(null), parse("/leave"))
        assertEquals(ParsedCommand.Leave("The Cave"), parse("/l The Cave"))
        assertEquals(ParsedCommand.SayInRoom("The Cave", "hi all"), parse("/say The Cave hi all", inRoom))
        assertEquals(ParsedCommand.SayInRoom("Other", "hi all"), parse("/say Other hi all", inRoom))
        assertEquals(ParsedCommand.InvalidUsage("say", "<room> <message..>"), parse("/say Other", inRoom))
    }

    @Test
    fun messagesAndActions() {
        assertEquals(ParsedCommand.Msg("bob", "hi there"), parse("/msg bob hi there"))
        assertEquals(ParsedCommand.Msg("bob", "hi"), parse("/m bob   hi"))
        assertEquals(ParsedCommand.InvalidUsage("msg", "<user> <message..>"), parse("/msg bob"))
        assertEquals(ParsedCommand.Me("waves"), parse("/me waves"))
        assertEquals(ParsedCommand.InvalidUsage("me", "<something..>"), parse("/me"))
        assertEquals(ParsedCommand.OpenPm("carol"), parse("/pm carol"))
        assertEquals(ParsedCommand.OpenPm("bob"), parse("/pm", inPm))
        assertEquals(ParsedCommand.ClosePm(null), parse("/close"))
        assertEquals(ParsedCommand.ClosePm("x"), parse("/c x"))
    }

    @Test
    fun userCommandsDefaultToPrivateChatUser() {
        assertEquals(ParsedCommand.Browse("dave"), parse("/browse dave"))
        assertEquals(ParsedCommand.Browse("bob"), parse("/b", inPm))
        assertEquals(ParsedCommand.InvalidUsage("browse", "[user]"), parse("/b", inRoom))
        assertEquals(ParsedCommand.ShowUserInfo("bob"), parse("/whois", inPm))
        assertEquals(ParsedCommand.ShowUserInfo("x"), parse("/info x"))
        assertEquals(ParsedCommand.ShowUserInfo("x"), parse("/w x"))
        assertEquals(ParsedCommand.UserList(UserListAction.Ignore("bob")), parse("/ignore", inPm))
        assertEquals(ParsedCommand.UserList(UserListAction.Unignore("u")), parse("/unignore u"))
        assertEquals(ParsedCommand.UserList(UserListAction.Ban("u")), parse("/ban u extra"))
        assertEquals(ParsedCommand.UserList(UserListAction.Unban("u")), parse("/unban u"))
        assertEquals(ParsedCommand.UserList(UserListAction.AddBuddy("u")), parse("/add u"))
        assertEquals(ParsedCommand.UserList(UserListAction.AddBuddy("u")), parse("/buddy u"))
        assertEquals(ParsedCommand.UserList(UserListAction.RemoveBuddy("u")), parse("/rem u"))
        assertEquals(ParsedCommand.UserList(UserListAction.RemoveBuddy("u")), parse("/unbuddy u"))
    }

    @Test
    fun searchCommands() {
        assertEquals(ParsedCommand.Search("daft punk", SearchScope.Network), parse("/search daft punk"))
        assertEquals(ParsedCommand.Search("x", SearchScope.Network), parse("/s x"))
        assertEquals(ParsedCommand.Search("x y", SearchScope.Buddies), parse("/bsearch x y"))
        assertEquals(ParsedCommand.Search("x y", SearchScope.Buddies), parse("/bs x y"))
        assertEquals(ParsedCommand.Search("flac live", SearchScope.User("bob")), parse("/usearch bob flac live"))
        assertEquals(ParsedCommand.InvalidUsage("usearch", "<user> <query>"), parse("/us bob"))
        assertEquals(ParsedCommand.Search("flac", SearchScope.Room("Lobby")), parse("/rsearch flac", inRoom))
        assertEquals(ParsedCommand.Search("flac", SearchScope.Room("The Cave")), parse("/rs The Cave flac", inRoom))
        assertEquals(ParsedCommand.InvalidUsage("rsearch", "[room] <query>"), parse("/rsearch flac"))
        assertEquals(ParsedCommand.InvalidUsage("search", "<query>"), parse("/search"))
    }

    @Test
    fun statusCommands() {
        assertEquals(ParsedCommand.ToggleAway, parse("/away"))
        assertEquals(ParsedCommand.ToggleAway, parse("/a"))
        assertEquals(ParsedCommand.Back, parse("/back"))
        assertEquals(ParsedCommand.Clear, parse("/clear"))
        assertEquals(ParsedCommand.Clear, parse("/cl"))
        assertEquals(ParsedCommand.Help(""), parse("/help"))
        assertEquals(ParsedCommand.Help("join"), parse("/? join"))
        assertEquals(ParsedCommand.Unknown("frobnicate"), parse("/frobnicate now"))
    }

    @Test
    fun aliasManagement() {
        assertEquals(ParsedCommand.ListAliases, parse("/alias"))
        assertEquals(ParsedCommand.SetAlias("hi", "/me waves at $1"), parse("/alias hi /me waves at \$1"))
        assertEquals(ParsedCommand.SetAlias("hi", "hello"), parse("/alias /HI hello"))
        assertEquals(ParsedCommand.InvalidAlias("join"), parse("/alias join /msg x y"))
        assertEquals(ParsedCommand.InvalidAlias("bad!"), parse("/alias bad! x"))
        assertEquals(ParsedCommand.InvalidUsage("alias", "[name] [command..]"), parse("/alias onlyname"))
        assertEquals(ParsedCommand.RemoveAlias("hi"), parse("/unalias hi"))
        assertTrue(CommandParser.isValidAliasName("my_alias2"))
        assertFalse(CommandParser.isValidAliasName("search"))
        assertFalse(CommandParser.isValidAliasName("s"))
        assertTrue(CommandParser.isBuiltin("W"))
    }

    @Test
    fun userAliasesExpand() {
        val ctx = CommandContext(ChatTarget.Room("Lobby"), listOf("Lobby"), mapOf(
            "wave" to "/me waves at \$1",
            "lobby" to "/join Lobby",
            "dm" to "/msg \$1 \$*",
            "greet" to "/msg",
            "loop" to "/loop",
            "txt" to "just text",
        ))
        assertEquals(ParsedCommand.Me("waves at bob"), parse("/wave bob", ctx))
        assertEquals(ParsedCommand.Join("Lobby"), parse("/lobby", ctx))
        assertEquals(ParsedCommand.Msg("bob", "bob hello world"), parse("/dm bob hello world", ctx))
        assertEquals(ParsedCommand.Msg("bob", "hi"), parse("/greet bob hi", ctx)) // yer tutucu yok → ekle
        assertEquals(ParsedCommand.Say("just text"), parse("/txt", ctx))
        assertEquals(ParsedCommand.Unknown("loop"), parse("/loop", ctx))
    }

    @Test
    fun expandPlaceholders() {
        assertEquals("a b c", CommandParser.expand("a \$2 \$1", "c b"))
        assertEquals("x  y", CommandParser.expand("x \$3 y", "only"))
        assertEquals("cmd one two", CommandParser.expand("cmd", "one two"))
        assertEquals("cmd", CommandParser.expand("cmd", ""))
        assertEquals("price \$", CommandParser.expand("price \$", ""))
    }

    @Test
    fun helpFilters() {
        assertTrue(CommandParser.help("").size >= 20)
        assertEquals(listOf("join"), CommandParser.help("/join").map { it.name })
        assertEquals(listOf("search", "rsearch", "bsearch", "usearch"), CommandParser.help("search").map { it.name })
        assertTrue(CommandParser.help("zzz").isEmpty())
    }
}
