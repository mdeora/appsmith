package com.appsmith.server.controllers;

import com.appsmith.server.constants.Url;
import com.appsmith.server.domains.Comment;
import com.appsmith.server.domains.CommentThread;
import com.appsmith.server.dtos.ResponseDTO;
import com.appsmith.server.services.CommentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.validation.Valid;
import java.util.List;

@Slf4j
@RestController
@RequestMapping(Url.COMMENT_URL)
public class CommentController extends BaseController<CommentService, Comment, String> {

    @Autowired
    public CommentController(CommentService service) {
        super(service);
    }

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ResponseDTO<Comment>> create(@Valid @RequestBody Comment resource,
                                             @RequestParam String threadId,
                                             ServerWebExchange exchange) {
        log.debug("Going to create resource {}", resource.getClass().getName());
        return service.create(threadId, resource)
                .map(created -> new ResponseDTO<>(HttpStatus.CREATED.value(), created, null));
    }

    @PostMapping("/threads")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ResponseDTO<CommentThread>> createThread(@Valid @RequestBody CommentThread resource,
                                                         ServerWebExchange exchange) {
        log.debug("Going to create resource {}", resource.getClass().getName());
        return service.createThread(resource)
                .map(created -> new ResponseDTO<>(HttpStatus.CREATED.value(), created, null));
    }

    @GetMapping("/threads")
    public Mono<ResponseDTO<List<CommentThread>>> getCommentThread(
            @RequestParam String applicationId
    ) {
        return service.getThreadsByApplicationId(applicationId)
                .map(threads -> new ResponseDTO<>(HttpStatus.OK.value(), threads, null));
    }

}