package dev.dwij.chat.message.api;

import dev.dwij.chat.common.model.ChatMessage;
import dev.dwij.chat.common.model.Conversation;
import dev.dwij.chat.common.model.ConversationType;
import dev.dwij.chat.message.config.MessageProperties;
import dev.dwij.chat.message.security.TokenFilter;
import dev.dwij.chat.message.store.ConversationStore;
import dev.dwij.chat.message.store.MemberDirectory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationStore conversations;
    private final MemberDirectory members;
    private final HistoryService history;
    private final MessageProperties properties;

    public ConversationController(ConversationStore conversations,
                                  MemberDirectory members,
                                  HistoryService history,
                                  MessageProperties properties) {
        this.conversations = conversations;
        this.members = members;
        this.history = history;
        this.properties = properties;
    }

    @PostMapping
    public ResponseEntity<Conversation> create(@Valid @RequestBody Requests.CreateConversation request,
                                               HttpServletRequest httpRequest) {
        String caller = caller(httpRequest);
        LinkedHashSet<String> roster = new LinkedHashSet<>(request.members());
        roster.add(caller);

        if (request.type() == ConversationType.DIRECT && roster.size() != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A direct conversation needs exactly two members");
        }
        if (roster.size() > properties.getMaxGroupSize()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A group is capped at " + properties.getMaxGroupSize() + " members");
        }

        Conversation conversation = new Conversation(
                conversationId(request.type(), roster),
                request.type(),
                new ArrayList<>(roster),
                request.title(),
                System.currentTimeMillis());

        Conversation created = conversations.create(conversation);
        members.invalidate(created.conversationId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{conversationId}")
    public Conversation get(@PathVariable String conversationId, HttpServletRequest httpRequest) {
        Conversation conversation = conversations.find(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such conversation"));
        requireMembership(conversation.members(), caller(httpRequest));
        return conversation;
    }

    /**
     * Newest first. Paging walks backwards by passing the oldest sequence seen so far as beforeSeq, which
     * is stable under concurrent writes in a way that an offset would not be: new messages arriving while
     * someone scrolls cannot shift the page boundaries underneath them.
     */
    @GetMapping("/{conversationId}/messages")
    public Requests.MessagePage messages(@PathVariable String conversationId,
                                         @RequestParam(required = false) Long beforeSeq,
                                         @RequestParam(defaultValue = "50") int limit,
                                         HttpServletRequest httpRequest) {
        requireMembership(members.members(conversationId), caller(httpRequest));
        List<ChatMessage> page = history.history(conversationId, beforeSeq, limit);
        Long next = page.isEmpty() ? null : page.get(page.size() - 1).getServerSeq();
        return new Requests.MessagePage(page, next, beforeSeq == null && limit <= properties.getRecentCacheSize());
    }

    private String caller(HttpServletRequest request) {
        Object caller = request.getAttribute(TokenFilter.CALLER);
        if (caller == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated caller");
        }
        return caller.toString();
    }

    private void requireMembership(List<String> roster, String caller) {
        if (!roster.contains(caller)) {
            // 404 rather than 403: confirming a conversation exists is itself a leak.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such conversation");
        }
    }

    /**
     * A direct conversation gets a deterministic id derived from its two members, so both sides land on
     * the same conversation without a lookup and opening a chat twice cannot create two of them.
     */
    private String conversationId(ConversationType type, LinkedHashSet<String> roster) {
        if (type == ConversationType.DIRECT) {
            List<String> pair = new ArrayList<>(roster);
            pair.sort(String::compareTo);
            return "dm:" + pair.get(0) + ":" + pair.get(1);
        }
        return "grp:" + UUID.randomUUID();
    }
}
