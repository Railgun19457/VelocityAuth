package io.github.railgun19457.velocityauth.command;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.velocitypowered.api.command.CommandSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Brigadier 补全提供器的前缀过滤行为。
 * <p>
 * 重构前这些断言挂在各命令的 {@code SimpleCommand#suggest} 上；改成 Brigadier 后，
 * 过滤逻辑统一收敛到 {@link CommandTree#adapt}。
 */
@Timeout(30)
class CommandSuggestionTest {

    private static List<String> suggest(List<String> candidates, String input) throws Exception {
        SuggestionsBuilder builder = new SuggestionsBuilder(input, 0);
        Suggestions result = CommandTree.adapt((CommandSource) -> candidates)
                .getSuggestions(null, builder)
                .join();
        return result.getList().stream().map(Suggestion::getText).toList();
    }

    @Test
    void emptyInputListsAllCandidates() throws Exception {
        assertEquals(List.of("email", "qq"), suggest(List.of("email", "qq"), ""));
    }

    @Test
    void prefixFiltersCandidates() throws Exception {
        assertEquals(List.of("email"), suggest(List.of("email", "qq"), "e"));
        assertEquals(List.of("qq"), suggest(List.of("email", "qq"), "q"));
        assertEquals(List.of(), suggest(List.of("email", "qq"), "x"));
    }

    @Test
    void prefixIsCaseInsensitive() throws Exception {
        assertEquals(List.of("email"), suggest(List.of("email", "qq"), "E"));
        assertEquals(List.of("email"), suggest(List.of("email", "qq"), "EMAIL"));
    }

    @Test
    void failingProviderYieldsEmptyRatherThanError() throws Exception {
        SuggestionsBuilder builder = new SuggestionsBuilder("", 0);
        Suggestions result = CommandTree.adapt(ctx -> {
            throw new IllegalStateException("boom");
        }).getSuggestions(null, builder).join();
        assertEquals(List.of(), result.getList());
    }

    @Test
    void nullProviderResultYieldsEmpty() throws Exception {
        SuggestionsBuilder builder = new SuggestionsBuilder("", 0);
        Suggestions result = CommandTree.adapt(ctx -> null).getSuggestions(null, builder).join();
        assertEquals(List.of(), result.getList());
    }
}
