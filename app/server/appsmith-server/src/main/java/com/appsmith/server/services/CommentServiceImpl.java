package com.appsmith.server.services;

import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.acl.PolicyGenerator;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.Comment;
import com.appsmith.server.domains.CommentThread;
import com.appsmith.server.domains.User;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.repositories.CommentRepository;
import com.appsmith.server.repositories.CommentThreadRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import javax.validation.Validator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class CommentServiceImpl extends BaseService<CommentRepository, Comment, String> implements CommentService {

    // TODO: Set permissions on the comment and thread objects directly, so we don't have to look up the application.

    private final CommentThreadRepository threadRepository;

    private final SessionUserService sessionUserService;
    private final ApplicationService applicationService;

    private final PolicyGenerator policyGenerator;

    public CommentServiceImpl(
            Scheduler scheduler,
            Validator validator,
            MongoConverter mongoConverter,
            ReactiveMongoTemplate reactiveMongoTemplate,
            CommentRepository repository,
            AnalyticsService analyticsService,
            CommentThreadRepository threadRepository,
            SessionUserService sessionUserService,
            ApplicationService applicationService,
            PolicyGenerator policyGenerator
    ) {
        super(scheduler, validator, mongoConverter, reactiveMongoTemplate, repository, analyticsService);
        this.threadRepository = threadRepository;
        this.sessionUserService = sessionUserService;
        this.applicationService = applicationService;
        this.policyGenerator = policyGenerator;
    }

    @Override
    public Mono<Comment> create(String threadId, Comment comment) {
        if (StringUtils.isWhitespace(comment.getAuthorName())) {
            // Error: User can't explicitly set the author name. It will be the currently logged in user.
            return Mono.empty();
        }

        return threadRepository.findById(threadId, AclPermission.COMMENT_ON_THREAD)
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.ACL_NO_RESOURCE_FOUND, "comment thread", threadId)))
                .flatMap(thread -> {
                    comment.setThreadId(threadId);
                    comment.setPolicies(policyGenerator.getAllChildPolicies(
                            thread.getPolicies(),
                            CommentThread.class,
                            Comment.class
                    ));
                    return Mono.zip(
                            Mono.just(comment),
                            sessionUserService.getCurrentUser()
                    );
                })
                .flatMap(tuple -> {
                    final Comment comment1 = tuple.getT1();
                    final User user = tuple.getT2();
                    comment1.setAuthorName(user.getName());
                    return repository.save(comment1);
                });
    }

    @Override
    public Mono<CommentThread> createThread(CommentThread commentThread) {
        // 1. Check if this user has permission on the application given by `commentThread.applicationId`.
        // 2. Save the comment thread and get it's id. This is the `threadId`.
        // 3. Pull the comment out of the list of comments, set it's `threadId` and save it separately.
        // 4. Populate the new comment's ID into the CommentThread object sent as response.

        final String applicationId = commentThread.getApplicationId();

        return applicationService.findById(applicationId, AclPermission.COMMENT_ON_APPLICATIONS)
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.ACL_NO_RESOURCE_FOUND, FieldName.APPLICATION, applicationId)))
                .flatMap(application -> {
                    commentThread.setPolicies(policyGenerator.getAllChildPolicies(
                            application.getPolicies(),
                            Application.class,
                            CommentThread.class
                    ));
                    return sessionUserService.getCurrentUser();
                })
                .flatMap(ignored -> threadRepository.save(commentThread))
                .flatMapMany(thread -> {
                    List<Mono<Comment>> commentSaverMonos = new ArrayList<>();

                    if (!CollectionUtils.isEmpty(thread.getComments())) {
                        for (final Comment comment : thread.getComments()) {
                            comment.setId(null);
                            commentSaverMonos.add(create(thread.getId(), comment));
                        }
                    }

                    // Using `concat` here so that the comments are saved one after the other, so that their `createdAt`
                    // value is meaningful.
                    return Flux.concat(commentSaverMonos);
                })
                .collectList()
                .map(comments -> {
                    commentThread.setComments(comments);
                    return commentThread;
                });
    }

    @Override
    public Mono<CommentThread> updateThread(String threadId, CommentThread commentThread) {
        final CommentThread updates = new CommentThread();

        // Copy over only those fields that are allowed to be updated by a PUT request.
        updates.setResolved(commentThread.getResolved());

        return threadRepository.updateById(threadId, commentThread, AclPermission.MANAGE_THREAD);
    }

    @Override
    public Mono<List<CommentThread>> getThreadsByApplicationId(String applicationId) {
        return threadRepository.findByApplicationId(applicationId, AclPermission.READ_THREAD)
                .collectList()
                .flatMap(threads -> {
                    final Map<String, CommentThread> threadsByThreadId = new HashMap<>();

                    for (CommentThread thread : threads) {
                        thread.setComments(new LinkedList<>());
                        threadsByThreadId.put(thread.getId(), thread);
                    }

                    return repository.findByThreadIdInOrderByCreatedAt(new ArrayList<>(threadsByThreadId.keySet()))
                            // TODO: Can we use `doOnSuccess` here?
                            .map(comment -> {
                                threadsByThreadId.get(comment.getThreadId()).getComments().add(comment);
                                return comment;
                            })
                            .then()
                            .thenReturn(threads);
                });
    }

}