/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.rest.api.service.impl;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;
import io.gravitee.common.data.domain.Page;
import io.gravitee.el.TemplateEngine;
import io.gravitee.el.spel.function.JsonPathFunction;
import io.gravitee.repository.exceptions.TechnicalException;
import io.gravitee.repository.management.api.UserRepository;
import io.gravitee.repository.management.api.search.UserCriteria;
import io.gravitee.repository.management.api.search.builder.PageableBuilder;
import io.gravitee.repository.management.model.User;
import io.gravitee.repository.management.model.UserReferenceType;
import io.gravitee.repository.management.model.UserStatus;
import io.gravitee.rest.api.model.*;
import io.gravitee.rest.api.model.application.ApplicationSettings;
import io.gravitee.rest.api.model.application.SimpleApplicationSettings;
import io.gravitee.rest.api.model.common.Pageable;
import io.gravitee.rest.api.model.configuration.identity.GroupMappingEntity;
import io.gravitee.rest.api.model.configuration.identity.RoleMappingEntity;
import io.gravitee.rest.api.model.configuration.identity.SocialIdentityProviderEntity;
import io.gravitee.rest.api.model.parameters.Key;
import io.gravitee.rest.api.model.permissions.RoleScope;
import io.gravitee.rest.api.model.permissions.SystemRole;
import io.gravitee.rest.api.service.*;
import io.gravitee.rest.api.service.builder.EmailNotificationBuilder;
import io.gravitee.rest.api.service.common.GraviteeContext;
import io.gravitee.rest.api.service.common.JWTHelper.ACTION;
import io.gravitee.rest.api.service.common.JWTHelper.Claims;
import io.gravitee.rest.api.service.common.RandomString;
import io.gravitee.rest.api.service.configuration.identity.IdentityProviderService;
import io.gravitee.rest.api.service.exceptions.*;
import io.gravitee.rest.api.service.impl.search.SearchResult;
import io.gravitee.rest.api.service.notification.NotificationParamsBuilder;
import io.gravitee.rest.api.service.notification.PortalHook;
import io.gravitee.rest.api.service.search.SearchEngineService;
import io.gravitee.rest.api.service.search.query.Query;
import io.gravitee.rest.api.service.search.query.QueryBuilder;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.xml.bind.DatatypeConverter;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static io.gravitee.repository.management.model.Audit.AuditProperties.USER;
import static io.gravitee.rest.api.service.common.JWTHelper.ACTION.*;
import static io.gravitee.rest.api.service.common.JWTHelper.DefaultValues.DEFAULT_JWT_EMAIL_REGISTRATION_EXPIRE_AFTER;
import static io.gravitee.rest.api.service.common.JWTHelper.DefaultValues.DEFAULT_JWT_ISSUER;
import static io.gravitee.rest.api.service.notification.NotificationParamsBuilder.REGISTRATION_PATH;
import static io.gravitee.rest.api.service.notification.NotificationParamsBuilder.RESET_PASSWORD_PATH;
import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author Azize Elamrani (azize.elamrani at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class UserServiceImpl extends AbstractService implements UserService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserServiceImpl.class);

    /** A default source used for user registration.*/
    private static final String IDP_SOURCE_GRAVITEE = "gravitee";
    private static final String TEMPLATE_ENGINE_PROFILE_ATTRIBUTE = "profile";

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ConfigurableEnvironment environment;
    @Autowired
    private EmailService emailService;
    @Autowired
    private ApplicationService applicationService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private MembershipService membershipService;
    @Autowired
    private AuditService auditService;
    @Autowired
    private NotifierService notifierService;
    @Autowired
    private ApiService apiService;
    @Autowired
    private ParameterService parameterService;
    @Autowired
    private SearchEngineService searchEngineService;
    @Autowired
    private InvitationService invitationService;
    @Autowired
    private PortalNotificationService portalNotificationService;
    @Autowired
    private PortalNotificationConfigService portalNotificationConfigService;
    @Autowired
    private GenericNotificationConfigService genericNotificationConfigService;
    @Autowired
    private GroupService groupService;
    @Autowired
    private OrganizationService organizationService;
    @Autowired
    private NewsletterService newsletterService;
    @Autowired
    private PasswordValidator passwordValidator;
    @Autowired
    private TokenService tokenService;
    @Autowired
    private IdentityProviderService identityProviderService;

    @Value("${user.login.defaultApplication:true}")
    private boolean defaultApplicationForFirstConnection;

    @Value("${user.anonymize-on-delete.enabled:false}")
    private boolean anonymizeOnDelete;

    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // Dirty hack: only used to force class loading
    static {
        try {
            LOGGER.trace("Loading class to initialize properly JsonPath Cache provider: {}",
                    Class.forName(JsonPathFunction.class.getName()));
        } catch (ClassNotFoundException ignored) {
            LOGGER.trace("Loading class to initialize properly JsonPath Cache provider : fail");
        }
    }

    @Override
    public UserEntity connect(String userId) {
        try {
            LOGGER.debug("Connection of {}", userId);
            Optional<User> checkUser = userRepository.findById(userId);
            if (!checkUser.isPresent()) {
                throw new UserNotFoundException(userId);
            }

            User user = checkUser.get();
            User previousUser = new User(user);
            // First connection: create default application for user & notify
            if (user.getLastConnectionAt() == null) {
                notifierService.trigger(PortalHook.USER_FIRST_LOGIN, new NotificationParamsBuilder()
                        .user(convert(user, false))
                        .build());
                if (defaultApplicationForFirstConnection) {
                    LOGGER.debug("Create a default application for {}", userId);
                    NewApplicationEntity defaultApp = new NewApplicationEntity();
                    defaultApp.setName("Default application");
                    defaultApp.setDescription("My default application");

                    // To preserve backward compatibility, ensure that we have at least default settings for simple application type
                    ApplicationSettings settings = new ApplicationSettings();
                    SimpleApplicationSettings simpleAppSettings = new SimpleApplicationSettings();
                    settings.setApp(simpleAppSettings);
                    defaultApp.setSettings(settings);

                    try {
                        applicationService.create(defaultApp, userId);
                    } catch (IllegalStateException ex) {
                        //do not fail to create a user even if we are not able to create its default app
                    }
                }
            }

            // Set date fields
            user.setLastConnectionAt(new Date());
            user.setUpdatedAt(user.getLastConnectionAt());

            user.setLoginCount(user.getLoginCount() + 1);

            User updatedUser = userRepository.update(user);
            auditService.createPortalAuditLog(
                    Collections.singletonMap(USER, userId),
                    User.AuditEvent.USER_CONNECTED,
                    user.getUpdatedAt(),
                    previousUser,
                    user);

            final UserEntity userEntity = convert(updatedUser, true);
            searchEngineService.index(userEntity, false);
            return userEntity;
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to connect {}", userId, ex);
            throw new TechnicalManagementException("An error occurs while trying to connect " + userId, ex);
        }
    }

    @Override
    public UserEntity findById(String id) {
        try {
            LOGGER.debug("Find user by ID: {}", id);

            Optional<User> optionalUser = userRepository.findById(id);

            if (optionalUser.isPresent()) {
                return convert(optionalUser.get(), false);
            }
            //should never happen
            throw new UserNotFoundException(id);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to find user using its ID {}", id, ex);
            throw new TechnicalManagementException("An error occurs while trying to find user using its ID " + id, ex);
        }
    }

    @Override
    public UserEntity findByIdWithRoles(String id) {
        try {
            LOGGER.debug("Find user by ID: {}", id);

            Optional<User> optionalUser = userRepository.findById(id);

            if (optionalUser.isPresent()) {
                return convert(optionalUser.get(), true);
            }
            //should never happen
            throw new UserNotFoundException(id);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to find user using its ID {}", id, ex);
            throw new TechnicalManagementException("An error occurs while trying to find user using its ID " + id, ex);
        }
    }

    @Override
    public UserEntity findBySource(String source, String sourceId, boolean loadRoles) {
        try {
            LOGGER.debug("Find user by source[{}] user[{}]", source, sourceId);

            Optional<User> optionalUser = userRepository.findBySource(source, sourceId, GraviteeContext.getCurrentOrganization(), UserReferenceType.ORGANIZATION);

            if (optionalUser.isPresent()) {
                return convert(optionalUser.get(), loadRoles);
            }

            throw new UserNotFoundException(sourceId);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to find user using source[{}], user[{}]", source, sourceId, ex);
            throw new TechnicalManagementException("An error occurs while trying to find user using source " + source + ':' + sourceId, ex);
        }
    }

    @Override
    public Set<UserEntity> findByIds(List<String> ids) {
        try {
            LOGGER.debug("Find users by ID: {}", ids);

            Set<User> users = userRepository.findByIds(ids);

            if (!users.isEmpty()) {
                return users.stream().map(u -> this.convert(u, false)).collect(Collectors.toSet());
            }

            Optional<String> idsAsString = ids.stream().reduce((a, b) -> a + '/' + b);
            if (idsAsString.isPresent()) {
                throw new UserNotFoundException(idsAsString.get());
            } else {
                throw new UserNotFoundException("?");
            }
        } catch (TechnicalException ex) {
            Optional<String> idsAsString = ids.stream().reduce((a, b) -> a + '/' + b);
            LOGGER.error("An error occurs while trying to find users using their ID {}", idsAsString, ex);
            throw new TechnicalManagementException("An error occurs while trying to find users using their ID " + idsAsString, ex);
        }
    }

    private void checkUserRegistrationEnabled() {
        if (!parameterService.findAsBoolean(Key.PORTAL_USERCREATION_ENABLED)) {
            throw new UserRegistrationUnavailableException();
        }
    }

    /**
     * Allows to complete the creation of a user which is pre-created.
     * @param registerUserEntity a valid token and a password
     * @return the user
     */
    @Override
    public UserEntity finalizeRegistration(final RegisterUserEntity registerUserEntity) {
        try {
            final String jwtSecret = environment.getProperty("jwt.secret");
            if (jwtSecret == null || jwtSecret.isEmpty()) {
                throw new IllegalStateException("JWT secret is mandatory");
            }

            Algorithm algorithm = Algorithm.HMAC256(jwtSecret);
            JWTVerifier verifier = JWT.require(algorithm)
                    .withIssuer(environment.getProperty("jwt.issuer", DEFAULT_JWT_ISSUER))
                    .build();

            DecodedJWT jwt = verifier.verify(registerUserEntity.getToken());

            final String action = jwt.getClaim(Claims.ACTION).asString();
            if (USER_REGISTRATION.name().equals(action)) {
                checkUserRegistrationEnabled();
            } else if (GROUP_INVITATION.name().equals(action)) {
                // check invitations
                final String email = jwt.getClaim(Claims.EMAIL).asString();
                final List<InvitationEntity> invitations = invitationService.findAll();
                final List<InvitationEntity> userInvitations = invitations.stream()
                        .filter(invitation -> invitation.getEmail().equals(email))
                        .collect(toList());
                if (userInvitations.isEmpty()) {
                    throw new IllegalStateException("Invitation has been canceled");
                }
            }
            final Object subject = jwt.getSubject();
            User user;
            if (subject == null) {
                final NewExternalUserEntity externalUser = new NewExternalUserEntity();
                final String email = jwt.getClaim(Claims.EMAIL).asString();
                externalUser.setSource(IDP_SOURCE_GRAVITEE);
                externalUser.setSourceId(email);
                externalUser.setFirstname(registerUserEntity.getFirstname());
                externalUser.setLastname(registerUserEntity.getLastname());
                externalUser.setEmail(email);
                user = convert(create(externalUser, true));
            } else {
                final String username = subject.toString();
                LOGGER.debug("Create an internal user {}", username);
                Optional<User> checkUser = userRepository.findById(username);
                user = checkUser.orElseThrow(() -> new UserNotFoundException(username));
                if (StringUtils.isNotBlank(user.getPassword())) {
                    throw new UserAlreadyFinalizedException(GraviteeContext.getCurrentEnvironment());
                }
            }

            if (GROUP_INVITATION.name().equals(action)) {
                // check invitations
                final String email = user.getEmail();
                final String userId = user.getId();
                final List<InvitationEntity> invitations = invitationService.findAll();
                invitations.stream()
                        .filter(invitation -> invitation.getEmail().equals(email))
                        .forEach(invitation -> {
                            invitationService.addMember(invitation.getReferenceType().name(), invitation.getReferenceId(),
                                    userId, invitation.getApiRole(), invitation.getApplicationRole());
                            invitationService.delete(invitation.getId(), invitation.getReferenceId());
                        });
            }

            // Set date fields
            user.setUpdatedAt(new Date());

            // Encrypt password if internal user
            if (registerUserEntity.getPassword() != null) {
                if (passwordValidator.validate(registerUserEntity.getPassword())) {
                    user.setPassword(passwordEncoder.encode(registerUserEntity.getPassword()));
                } else {
                    throw new PasswordFormatInvalidException();
                }
            }

            user = userRepository.update(user);
            auditService.createPortalAuditLog(
                    Collections.singletonMap(USER, user.getId()),
                    User.AuditEvent.USER_CREATED,
                    user.getUpdatedAt(),
                    null,
                    user);

            // Do not send back the password
            user.setPassword(null);

            final UserEntity userEntity = convert(user, true);
            searchEngineService.index(userEntity, false);
            return userEntity;
        } catch (AbstractManagementException ex) {
            throw ex;
        } catch (Exception ex) {
            LOGGER.error("An error occurs while trying to create an internal user with the token {}", registerUserEntity.getToken(), ex);
            throw new TechnicalManagementException(ex.getMessage(), ex);
        }
    }

    @Override
    public PictureEntity getPicture(String id) {
        UserEntity user = findById(id);

        if (user.getPicture() != null) {
            String picture = user.getPicture();

            if (picture.matches("^(http|https)://.*$")) {
                return new UrlPictureEntity(picture);
            } else {
                try {
                    InlinePictureEntity imageEntity = new InlinePictureEntity();
                    String[] parts = picture.split(";", 2);
                    imageEntity.setType(parts[0].split(":")[1]);
                    String base64Content = picture.split(",", 2)[1];
                    imageEntity.setContent(DatatypeConverter.parseBase64Binary(base64Content));
                    return imageEntity;
                } catch (Exception ex) {
                    LOGGER.warn("Unable to get user picture for id[{}]", id);
                }
            }
        }

        // Return empty image
        InlinePictureEntity imageEntity = new InlinePictureEntity();
        imageEntity.setType("image/png");
        return imageEntity;
    }

    /**
     * Allows to create a user.
     * @param newExternalUserEntity
     * @return
     */
    @Override
    public UserEntity create(NewExternalUserEntity newExternalUserEntity, boolean addDefaultRole) {
        try {
            String referenceId = GraviteeContext.getCurrentOrganization();
            UserReferenceType referenceType = UserReferenceType.ORGANIZATION;

            // First we check that organization exist
            this.organizationService.findById(referenceId);

            LOGGER.debug("Create an external user {}", newExternalUserEntity);
            Optional<User> checkUser = userRepository.findBySource(
                    newExternalUserEntity.getSource(), newExternalUserEntity.getSourceId(), referenceId, referenceType);

            if (checkUser.isPresent()) {
                throw new UserAlreadyExistsException(newExternalUserEntity.getSource(), newExternalUserEntity.getSourceId(), referenceId, referenceType);
            }

            User user = convert(newExternalUserEntity);
            user.setId(RandomString.generate());
            user.setReferenceId(referenceId);
            user.setReferenceType(referenceType);
            user.setStatus(UserStatus.ACTIVE);

            // Set date fields
            user.setCreatedAt(new Date());
            user.setUpdatedAt(user.getCreatedAt());

            User createdUser = userRepository.create(user);
            auditService.createPortalAuditLog(
                    Collections.singletonMap(USER, user.getId()),
                    User.AuditEvent.USER_CREATED,
                    user.getCreatedAt(),
                    null,
                    user);

            if (addDefaultRole) {
                addDefaultMembership(createdUser);
            }

            final UserEntity userEntity = convert(createdUser, true);
            searchEngineService.index(userEntity, false);
            return userEntity;
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to create an external user {}", newExternalUserEntity, ex);
            throw new TechnicalManagementException("An error occurs while trying to create an external user" + newExternalUserEntity, ex);
        }
    }

    private void addDefaultMembership(User user) {
        RoleScope[] scopes = {RoleScope.ORGANIZATION, RoleScope.ENVIRONMENT};
        List<RoleEntity> defaultRoleByScopes = roleService.findDefaultRoleByScopes(scopes);
        if (defaultRoleByScopes == null || defaultRoleByScopes.isEmpty()) {
            throw new DefaultRoleNotFoundException(scopes);
        }

        for (RoleEntity defaultRoleByScope : defaultRoleByScopes) {
            switch (defaultRoleByScope.getScope()) {
                case ORGANIZATION:
                    membershipService.addRoleToMemberOnReference(
                            new MembershipService.MembershipReference(MembershipReferenceType.ORGANIZATION, GraviteeContext.getCurrentOrganization()),
                            new MembershipService.MembershipMember(user.getId(), null, MembershipMemberType.USER),
                            new MembershipService.MembershipRole(RoleScope.ORGANIZATION, defaultRoleByScope.getName()));
                    break;
                case ENVIRONMENT:
                    membershipService.addRoleToMemberOnReference(
                            new MembershipService.MembershipReference(MembershipReferenceType.ENVIRONMENT, GraviteeContext.getCurrentEnvironment()),
                            new MembershipService.MembershipMember(user.getId(), null, MembershipMemberType.USER),
                            new MembershipService.MembershipRole(RoleScope.ENVIRONMENT, defaultRoleByScope.getName()));
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    public UserEntity register(final NewExternalUserEntity newExternalUserEntity) {
        return register(newExternalUserEntity, null);
    }

    @Override
    public UserEntity register(final NewExternalUserEntity newExternalUserEntity, final String confirmationPageUrl) {
        checkUserRegistrationEnabled();
        return createAndSendEmail(newExternalUserEntity, USER_REGISTRATION, confirmationPageUrl);
    }

    @Override
    public UserEntity create(final NewExternalUserEntity newExternalUserEntity) {
        return createAndSendEmail(newExternalUserEntity, USER_CREATION, null);
    }

    /**
     * Allows to create an user and send an email notification to finalize its creation.
     */
    private UserEntity createAndSendEmail(final NewExternalUserEntity newExternalUserEntity, final ACTION action, final String confirmationPageUrl) {
        if (!EmailValidator.isValid(newExternalUserEntity.getEmail())) {
            throw new EmailFormatInvalidException(newExternalUserEntity.getEmail());
        }

        if (isBlank(newExternalUserEntity.getSource())) {
            newExternalUserEntity.setSource(IDP_SOURCE_GRAVITEE);
        } else {
            if (!IDP_SOURCE_GRAVITEE.equals(newExternalUserEntity.getSource())) {
                // check if IDP exists
                identityProviderService.findById(newExternalUserEntity.getSource());
            }
        }

        if (isBlank(newExternalUserEntity.getSourceId())) {
            newExternalUserEntity.setSourceId(newExternalUserEntity.getEmail());
        }

        String referenceId = GraviteeContext.getCurrentOrganization();
        UserReferenceType referenceType = UserReferenceType.ORGANIZATION;

        final Optional<User> optionalUser;
        try {
            optionalUser = userRepository.findBySource(newExternalUserEntity.getSource(), newExternalUserEntity.getSourceId(), referenceId, referenceType);
            if (optionalUser.isPresent()) {
                throw new UserAlreadyExistsException(newExternalUserEntity.getSource(), newExternalUserEntity.getSourceId(), referenceId, referenceType);
            }
        } catch (final TechnicalException e) {
            LOGGER.error("An error occurs while trying to create user {} / {}", newExternalUserEntity.getSource(), newExternalUserEntity.getSourceId(), e);
            throw new TechnicalManagementException(e.getMessage(), e);
        }

        newExternalUserEntity.setSource(IDP_SOURCE_GRAVITEE);
        newExternalUserEntity.setSourceId(newExternalUserEntity.getEmail());

        final UserEntity userEntity = create(newExternalUserEntity, true);

        if (IDP_SOURCE_GRAVITEE.equals(newExternalUserEntity.getSource())) {
            final Map<String, Object> params = getTokenRegistrationParams(userEntity, REGISTRATION_PATH, action, confirmationPageUrl);
            notifierService.trigger(ACTION.USER_REGISTRATION.equals(action) ? PortalHook.USER_REGISTERED : PortalHook.USER_CREATED, params);
            emailService.sendAsyncEmailNotification(new EmailNotificationBuilder()
                    .to(userEntity.getEmail())
                    .subject(format("User %s - %s", USER_REGISTRATION.equals(action) ? "registration" : "creation", userEntity.getDisplayName()))
                    .template(EmailNotificationBuilder.EmailTemplate.USER_REGISTRATION)
                    .params(params)
                    .build()
            );
        }

        if (newExternalUserEntity.isNewsletter()) {
            newsletterService.subscribe(newExternalUserEntity);
        }

        return userEntity;
    }

    @Override
    public Map<String, Object> getTokenRegistrationParams(final UserEntity userEntity, final String managementUri,
                                                          final ACTION action) {
        return getTokenRegistrationParams(userEntity, managementUri, action, null);
    }

    @Override
    public Map<String, Object> getTokenRegistrationParams(final UserEntity userEntity, final String managementUri,
                                                          final ACTION action, final String targetPageUrl) {
        // generate a JWT to store user's information and for security purpose
        final String jwtSecret = environment.getProperty("jwt.secret");
        if (jwtSecret == null || jwtSecret.isEmpty()) {
            throw new IllegalStateException("JWT secret is mandatory");
        }

        Algorithm algorithm = Algorithm.HMAC256(environment.getProperty("jwt.secret"));

        Date issueAt = new Date();
        Instant expireAt = issueAt.toInstant().plus(Duration.ofSeconds(environment.getProperty("user.creation.token.expire-after",
                Integer.class, DEFAULT_JWT_EMAIL_REGISTRATION_EXPIRE_AFTER)));

        final String token = JWT.create()
                .withIssuer(environment.getProperty("jwt.issuer", DEFAULT_JWT_ISSUER))
                .withIssuedAt(issueAt)
                .withExpiresAt(Date.from(expireAt))
                .withSubject(userEntity.getId())
                .withClaim(Claims.EMAIL, userEntity.getEmail())
                .withClaim(Claims.FIRSTNAME, userEntity.getFirstname())
                .withClaim(Claims.LASTNAME, userEntity.getLastname())
                .withClaim(Claims.ACTION, action.name())
                .sign(algorithm);

        String registrationUrl = "";
        if (targetPageUrl != null && !targetPageUrl.isEmpty()) {
            registrationUrl += targetPageUrl;
            if (!targetPageUrl.endsWith("/")) {
                registrationUrl += "/";
            }
            registrationUrl += token;
        } else {
            String managementUrl = parameterService.find(Key.MANAGEMENT_URL);
            if (managementUrl != null && managementUrl.endsWith("/")) {
                managementUrl = managementUrl.substring(0, managementUrl.length() - 1);
            }
            registrationUrl = managementUrl + managementUri + token;
        }

        // send a confirm email with the token
        return new NotificationParamsBuilder()
                .user(userEntity)
                .token(token)
                .registrationUrl(registrationUrl)
                .build();
    }

    @Override
    public UserEntity update(String id, UpdateUserEntity updateUserEntity) {
        try {
            LOGGER.debug("Updating {}", updateUserEntity);
            Optional<User> checkUser = userRepository.findById(id);
            if (!checkUser.isPresent()) {
                throw new UserNotFoundException(id);
            }

            User user = checkUser.get();
            User previousUser = new User(user);

            // Set date fields
            user.setUpdatedAt(new Date());

            // Set variant fields
            user.setPicture(updateUserEntity.getPicture());

            if (updateUserEntity.getFirstname() != null) {
                user.setFirstname(updateUserEntity.getFirstname());
            }
            if (updateUserEntity.getLastname() != null) {
                user.setLastname(updateUserEntity.getLastname());
            }
            if (updateUserEntity.getEmail() != null) {
                user.setEmail(updateUserEntity.getEmail());
            }
            if (updateUserEntity.getStatus() != null) {
                user.setStatus(UserStatus.valueOf(updateUserEntity.getStatus()));
            }

            if (updateUserEntity.isNewsletter() && user.getEmail() != null) {
                newsletterService.subscribe(user);
            }

            User updatedUser = userRepository.update(user);
            auditService.createPortalAuditLog(
                    Collections.singletonMap(USER, user.getId()),
                    User.AuditEvent.USER_UPDATED,
                    user.getUpdatedAt(),
                    previousUser,
                    user);
            return convert(updatedUser, true);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to update {}", updateUserEntity, ex);
            throw new TechnicalManagementException("An error occurs while trying update " + updateUserEntity, ex);
        }
    }

    @Override
    public Page<UserEntity> search(String query, Pageable pageable) {
        LOGGER.debug("search users");

        if (query == null || query.isEmpty()) {
            return search(new UserCriteria.Builder().statuses(UserStatus.ACTIVE).build(), pageable);
        }

        Query<UserEntity> userQuery = QueryBuilder.create(UserEntity.class)
                .setQuery(query)
                .setPage(pageable)
                .build();

        SearchResult results = searchEngineService.search(userQuery);

        if (results.hasResults()) {
            List<UserEntity> users = new ArrayList<>((findByIds(results.getDocuments())));

            populateUserFlags(users);

            return new Page<>(users,
                    pageable.getPageNumber(),
                    pageable.getPageSize(),
                    results.getHits());
        }
        return new Page<>(Collections.emptyList(), 1, 0, 0);
    }

    private void populateUserFlags(final List<UserEntity> users) {
        RoleEntity apiPORole = roleService.findByScopeAndName(RoleScope.API, SystemRole.PRIMARY_OWNER.name())
                .orElseThrow(() -> new TechnicalManagementException("API System Role 'PRIMARY_OWNER' not found."));
        RoleEntity applicationPORole = roleService.findByScopeAndName(RoleScope.APPLICATION, SystemRole.PRIMARY_OWNER.name())
                .orElseThrow(() -> new TechnicalManagementException("API System Role 'PRIMARY_OWNER' not found."));

        users.forEach(user -> {
            final boolean apiPO = !membershipService.getMembershipsByMemberAndReferenceAndRole(
                    MembershipMemberType.USER, user.getId(), MembershipReferenceType.API, apiPORole.getId())
                    .isEmpty();
            final boolean appPO = !membershipService.getMembershipsByMemberAndReferenceAndRole(
                    MembershipMemberType.USER, user.getId(), MembershipReferenceType.APPLICATION, applicationPORole.getId())
                    .isEmpty();

            user.setPrimaryOwner(apiPO || appPO);
            user.setNbActiveTokens(tokenService.findByUser(user.getId()).size());
        });
    }


    @Override
    public Page<UserEntity> search(UserCriteria criteria, Pageable pageable) {
        try {
            LOGGER.debug("search users");
            UserCriteria.Builder builder = new UserCriteria.Builder()
                    .referenceId(GraviteeContext.getCurrentOrganization())
                    .referenceType(UserReferenceType.ORGANIZATION)
                    .statuses(criteria.getStatuses());
            if (criteria.hasNoStatus()) {
                builder.noStatus();
            }
            UserCriteria newCriteria = builder.build();

            Page<User> users = userRepository.search(newCriteria, new PageableBuilder()
                    .pageNumber(pageable.getPageNumber() - 1)
                    .pageSize(pageable.getPageSize())
                    .build());

            List<UserEntity> entities = users.getContent()
                    .stream()
                    .map(u -> convert(u, false))
                    .collect(toList());

            populateUserFlags(entities);

            return new Page<>(entities,
                    users.getPageNumber() + 1,
                    (int) users.getPageElements(),
                    users.getTotalElements());
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to search users", ex);
            throw new TechnicalManagementException("An error occurs while trying to search users", ex);
        }
    }

    @Override
    public void delete(String id) {
        try {
            // If the users is PO of apps or apis, throw an exception
            long apiCount = apiService.findByUser(id, null, false)
                    .stream()
                    .filter(entity -> entity.getPrimaryOwner().getId().equals(id))
                    .count();
            long applicationCount = applicationService.findByUser(id)
                    .stream()
                    .filter(app -> app.getPrimaryOwner() != null)
                    .filter(app -> app.getPrimaryOwner().getId().equals(id))
                    .count();
            if (apiCount > 0 || applicationCount > 0) {
                throw new StillPrimaryOwnerException(apiCount, applicationCount);
            }

            Optional<User> optionalUser = userRepository.findById(id);
            if (!optionalUser.isPresent()) {
                throw new UserNotFoundException(id);
            }

            membershipService.removeMemberMemberships(MembershipMemberType.USER, id);
            User user = optionalUser.get();

            //remove notifications
            portalNotificationService.deleteAll(user.getId());
            portalNotificationConfigService.deleteByUser(user.getId());
            genericNotificationConfigService.deleteByUser(user);

            //remove tokens
            tokenService.revokeByUser(user.getId());

            // change user datas
            user.setSourceId("deleted-" + user.getSourceId());
            user.setStatus(UserStatus.ARCHIVED);
            user.setUpdatedAt(new Date());

            if (anonymizeOnDelete) {
                User anonym = new User();
                anonym.setId(user.getId());
                anonym.setCreatedAt(user.getCreatedAt());
                anonym.setUpdatedAt(user.getUpdatedAt());
                anonym.setStatus(user.getStatus());
                anonym.setSource(user.getSource());
                anonym.setLastConnectionAt(user.getLastConnectionAt());
                anonym.setSourceId("deleted-" + user.getId());
                anonym.setFirstname("Unknown");
                anonym.setLastname("");
                anonym.setLoginCount(user.getLoginCount());
                user = anonym;
            }

            userRepository.update(user);


            final UserEntity userEntity = convert(optionalUser.get(), false);
            searchEngineService.delete(userEntity, false);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to delete user", ex);
            throw new TechnicalManagementException("An error occurs while trying to delete user", ex);
        }
    }

    @Override
    public void resetPassword(final String id) {
        this.resetPassword(id, null);
    }

    @Override
    public UserEntity resetPasswordFromSourceId(String sourceId, String resetPageUrl) {
        if (sourceId.startsWith("deleted")) {
            throw new UserNotActiveException(sourceId);
        }
        UserEntity foundUser = this.findBySource(IDP_SOURCE_GRAVITEE, sourceId, false);
        if ("ACTIVE".equals(foundUser.getStatus())) {
            this.resetPassword(foundUser.getId(), resetPageUrl);
            return foundUser;
        } else {
            throw new UserNotActiveException(foundUser.getSourceId());
        }
    }

    private void resetPassword(final String id, final String resetPageUrl) {
        try {
            LOGGER.debug("Resetting password of user id {}", id);

            Optional<User> optionalUser = userRepository.findById(id);

            if (!optionalUser.isPresent()) {
                throw new UserNotFoundException(id);
            }
            final User user = optionalUser.get();
            if (!IDP_SOURCE_GRAVITEE.equals(user.getSource())) {
                throw new UserNotInternallyManagedException(id);
            }
            user.setPassword(null);
            user.setUpdatedAt(new Date());
            userRepository.update(user);

            final Map<String, Object> params = getTokenRegistrationParams(convert(user, false),
                    RESET_PASSWORD_PATH, RESET_PASSWORD, resetPageUrl);

            notifierService.trigger(PortalHook.PASSWORD_RESET, params);

            auditService.createPortalAuditLog(
                    Collections.singletonMap(USER, user.getId()),
                    User.AuditEvent.PASSWORD_RESET,
                    user.getUpdatedAt(),
                    null,
                    null);
            emailService.sendAsyncEmailNotification(new EmailNotificationBuilder()
                    .to(user.getEmail())
                    .subject("Password reset - " + convert(user, false).getDisplayName())
                    .template(EmailNotificationBuilder.EmailTemplate.PASSWORD_RESET)
                    .params(params)
                    .build()
            );
        } catch (TechnicalException ex) {
            final String message = "An error occurs while trying to reset password for user " + id;
            LOGGER.error(message, ex);
            throw new TechnicalManagementException(message, ex);
        }
    }

    private User convert(NewExternalUserEntity newExternalUserEntity) {
        if (newExternalUserEntity == null) {
            return null;
        }
        User user = new User();
        user.setEmail(newExternalUserEntity.getEmail());
        user.setFirstname(newExternalUserEntity.getFirstname());
        user.setLastname(newExternalUserEntity.getLastname());
        user.setSource(newExternalUserEntity.getSource());
        user.setSourceId(newExternalUserEntity.getSourceId());
        user.setStatus(UserStatus.ACTIVE);
        user.setPicture(newExternalUserEntity.getPicture());
        return user;
    }

    private User convert(UserEntity userEntity) {
        if (userEntity == null) {
            return null;
        }
        User user = new User();
        user.setId(userEntity.getId());
        user.setEmail(userEntity.getEmail());
        user.setFirstname(userEntity.getFirstname());
        user.setLastname(userEntity.getLastname());
        user.setSource(userEntity.getSource());
        user.setSourceId(userEntity.getSourceId());
        if (userEntity.getStatus() != null) {
            user.setStatus(UserStatus.valueOf(userEntity.getStatus()));
        }
        return user;
    }

    private UserEntity convert(User user, boolean loadRoles) {
        if (user == null) {
            return null;
        }
        UserEntity userEntity = new UserEntity();

        userEntity.setId(user.getId());
        userEntity.setSource(user.getSource());
        userEntity.setSourceId(user.getSourceId());
        userEntity.setEmail(user.getEmail());
        userEntity.setFirstname(user.getFirstname());
        userEntity.setLastname(user.getLastname());
        userEntity.setPassword(user.getPassword());
        userEntity.setCreatedAt(user.getCreatedAt());
        userEntity.setUpdatedAt(user.getUpdatedAt());
        userEntity.setLastConnectionAt(user.getLastConnectionAt());
        userEntity.setPicture(user.getPicture());
        if (user.getStatus() != null) {
            userEntity.setStatus(user.getStatus().name());
        }

        if (loadRoles) {
            Set<UserRoleEntity> roles = new HashSet<>();
            Set<RoleEntity> roleEntities = membershipService.getRoles(
                    MembershipReferenceType.ORGANIZATION,
                    GraviteeContext.getCurrentOrganization(),
                    MembershipMemberType.USER,
                    user.getId());
            if (!roleEntities.isEmpty()) {
                roleEntities.forEach(roleEntity -> roles.add(convert(roleEntity)));
            }

            roleEntities = membershipService.getRoles(
                    MembershipReferenceType.ENVIRONMENT,
                    GraviteeContext.getCurrentEnvironment(),
                    MembershipMemberType.USER,
                    user.getId());
            if (!roleEntities.isEmpty()) {
                roleEntities.forEach(roleEntity -> roles.add(convert(roleEntity)));
            }

            userEntity.setRoles(roles);
        }

        userEntity.setLoginCount(user.getLoginCount());

        return userEntity;
    }

    private UserRoleEntity convert(RoleEntity roleEntity) {
        if (roleEntity == null) {
            return null;
        }

        UserRoleEntity userRoleEntity = new UserRoleEntity();
        userRoleEntity.setId(roleEntity.getId());
        userRoleEntity.setScope(roleEntity.getScope());
        userRoleEntity.setName(roleEntity.getName());
        userRoleEntity.setPermissions(roleEntity.getPermissions());
        return userRoleEntity;
    }

    @Override
    public UserEntity createOrUpdateUserFromSocialIdentityProvider(SocialIdentityProviderEntity socialProvider,
                                                                   String userInfo) {
        HashMap<String, String> attrs = getUserProfileAttrs(socialProvider.getUserProfileMapping(), userInfo);

        String email = attrs.get(SocialIdentityProviderEntity.UserProfile.EMAIL);
        if (email == null && socialProvider.isEmailRequired()) {
            throw new EmailRequiredException(attrs.get(SocialIdentityProviderEntity.UserProfile.ID));
        }

        try {
            return refreshExistingUser(socialProvider, attrs, email);
        } catch (UserNotFoundException unfe) {
            return createNewExternalUser(socialProvider, userInfo, attrs, email);
        }
    }

    private HashMap<String, String> getUserProfileAttrs(Map<String, String> userProfileMapping, String userInfo) {
        TemplateEngine templateEngine = TemplateEngine.templateEngine();
        templateEngine.getTemplateContext().setVariable(TEMPLATE_ENGINE_PROFILE_ATTRIBUTE, userInfo);

        ReadContext userInfoPath = JsonPath.parse(userInfo);
        HashMap<String, String> map = new HashMap<>(userProfileMapping.size());

        for (Map.Entry<String, String> entry : userProfileMapping.entrySet()) {
            String field = entry.getKey();
            String mapping = entry.getValue();

            if (mapping != null) {
                try {
                    if (mapping.contains("{#")) {
                        map.put(field, templateEngine.getValue(mapping, String.class));
                    } else {
                        map.put(field, userInfoPath.read(mapping, String.class));
                    }
                } catch (Exception e) {
                    LOGGER.error("Using mapping: \"{}\", no fields are located in {}", mapping, userInfo);
                }
            }
        }

        return map;
    }

    private UserEntity createNewExternalUser(final SocialIdentityProviderEntity socialProvider, final String userInfo, HashMap<String, String> attrs, String email) {
        final NewExternalUserEntity newUser = new NewExternalUserEntity();
        newUser.setEmail(email);
        newUser.setSource(socialProvider.getId());

        if (attrs.get(SocialIdentityProviderEntity.UserProfile.ID) != null) {
            newUser.setSourceId(attrs.get(SocialIdentityProviderEntity.UserProfile.ID));
        }
        if (attrs.get(SocialIdentityProviderEntity.UserProfile.LASTNAME) != null) {
            newUser.setLastname(attrs.get(SocialIdentityProviderEntity.UserProfile.LASTNAME));
        }
        if (attrs.get(SocialIdentityProviderEntity.UserProfile.FIRSTNAME) != null) {
            newUser.setFirstname(attrs.get(SocialIdentityProviderEntity.UserProfile.FIRSTNAME));
        }
        if (attrs.get(SocialIdentityProviderEntity.UserProfile.PICTURE) != null) {
            newUser.setPicture(attrs.get(SocialIdentityProviderEntity.UserProfile.PICTURE));
        }

        UserEntity createdUser = null;
        String userId = null;
        if (socialProvider.getGroupMappings() != null && !socialProvider.getGroupMappings().isEmpty()) {
            // Can fail if a mappingCondition is not well-formed
            Set<GroupEntity> groupsToAdd = getGroupsToAddUser(userId, socialProvider.getGroupMappings(), userInfo);
            createdUser = this.create(newUser, true);
            userId = createdUser.getId();
            addUserToApiAndAppGroupsWithDefaultRole(userId, groupsToAdd);
        } else {
            createdUser = this.create(newUser, true);
            userId = createdUser.getId();
        }

        if (socialProvider.getRoleMappings() != null && !socialProvider.getRoleMappings().isEmpty()) {
            Set<RoleEntity> rolesToAdd = getRolesToAddUser(userId, socialProvider.getRoleMappings(), userInfo);
            addRolesToUser(userId, rolesToAdd);
        }
        return createdUser;
    }

    private UserEntity refreshExistingUser(final SocialIdentityProviderEntity socialProvider, HashMap<String, String> attrs, String email) {
        String userId;
        UserEntity registeredUser = this.findBySource(socialProvider.getId(), attrs.get(SocialIdentityProviderEntity.UserProfile.ID), false);
        userId = registeredUser.getId();

        // User refresh
        UpdateUserEntity user = new UpdateUserEntity();

        if (attrs.get(SocialIdentityProviderEntity.UserProfile.LASTNAME) != null) {
            user.setLastname(attrs.get(SocialIdentityProviderEntity.UserProfile.LASTNAME));
        }
        if (attrs.get(SocialIdentityProviderEntity.UserProfile.FIRSTNAME) != null) {
            user.setFirstname(attrs.get(SocialIdentityProviderEntity.UserProfile.FIRSTNAME));
        }
        if (attrs.get(SocialIdentityProviderEntity.UserProfile.PICTURE) != null) {
            user.setPicture(attrs.get(SocialIdentityProviderEntity.UserProfile.PICTURE));
        }
        user.setEmail(email);

        return this.update(userId, user);
    }

    private Set<GroupEntity> getGroupsToAddUser(String userId, List<GroupMappingEntity> mappings, String userInfo) {
        Set<GroupEntity> groupsToAdd = new HashSet<>();

        for (GroupMappingEntity mapping : mappings) {
            TemplateEngine templateEngine = TemplateEngine.templateEngine();
            templateEngine.getTemplateContext().setVariable(TEMPLATE_ENGINE_PROFILE_ATTRIBUTE, userInfo);

            Boolean match = templateEngine.getValue(mapping.getCondition(), Boolean.class);

            trace(userId, match, mapping.getCondition());

            // Get groups
            if (match.booleanValue()) {
                for (String groupName : mapping.getGroups()) {
                    try {
                        groupsToAdd.add(groupService.findById(groupName));
                    } catch (GroupNotFoundException gnfe) {
                        LOGGER.error("Unable to create user, missing group in repository : {}", groupName);
                    }
                }
            }
        }
        return groupsToAdd;
    }

    private Set<RoleEntity> getRolesToAddUser(String username, List<RoleMappingEntity> mappings, String userInfo) {
        Set<RoleEntity> rolesToAdd = new HashSet<>();

        for (RoleMappingEntity mapping : mappings) {
            TemplateEngine templateEngine = TemplateEngine.templateEngine();
            templateEngine.getTemplateContext().setVariable(TEMPLATE_ENGINE_PROFILE_ATTRIBUTE, userInfo);

            boolean match = templateEngine.getValue(mapping.getCondition(), boolean.class);

            trace(username, match, mapping.getCondition());

            // Get roles
            if (match) {
                if (mapping.getOrganizations() != null && !mapping.getOrganizations().isEmpty()) {
                    mapping.getOrganizations().forEach(organizationRoleName -> addRoleScope(rolesToAdd, organizationRoleName, RoleScope.ORGANIZATION));
                }
                if (mapping.getEnvironments() != null && !mapping.getEnvironments().isEmpty()) {
                    mapping.getEnvironments().forEach(environmentRoleName -> addRoleScope(rolesToAdd, environmentRoleName, RoleScope.ENVIRONMENT));
                }
            }
        }
        return rolesToAdd;
    }

    private void addRoleScope(Set<RoleEntity> rolesToAdd, String mappingRole, RoleScope roleScope) {
        if (mappingRole != null) {
            Optional<RoleEntity> optRoleEntity = roleService.findByScopeAndName(roleScope, mappingRole);
            if (optRoleEntity.isPresent()) {
                rolesToAdd.add(optRoleEntity.get());
            } else {
                LOGGER.error("Unable to create user, missing role in repository : {}", mappingRole);
            }
        }
    }

    private void addUserToApiAndAppGroupsWithDefaultRole(String userId, Collection<GroupEntity> groupsToAdd) {
        // Get the default role from system
        List<RoleEntity> roleEntities = roleService.findDefaultRoleByScopes(RoleScope.API, RoleScope.APPLICATION);

        // Add groups to user
        for (GroupEntity groupEntity : groupsToAdd) {
            for (RoleEntity roleEntity : roleEntities) {
                String defaultRole = roleEntity.getName();

                // If defined, get the override default role at the group level
                if (groupEntity.getRoles() != null) {
                    String groupDefaultRole = groupEntity.getRoles().get(io.gravitee.rest.api.model.permissions.RoleScope.valueOf(roleEntity.getScope().name()));
                    if (groupDefaultRole != null) {
                        defaultRole = groupDefaultRole;
                    }
                }

                membershipService.addRoleToMemberOnReference(
                        new MembershipService.MembershipReference(MembershipReferenceType.GROUP, groupEntity.getId()),
                        new MembershipService.MembershipMember(userId, null, MembershipMemberType.USER),
                        new MembershipService.MembershipRole(roleEntity.getScope(), defaultRole));
            }
        }

    }

    private void addRolesToUser(String userId, Collection<RoleEntity> rolesToAdd) {
        // add roles to user
        for (RoleEntity roleEntity : rolesToAdd) {
            MembershipService.MembershipReference ref = null;
            if (roleEntity.getScope() == RoleScope.ORGANIZATION) {
                ref = new MembershipService.MembershipReference(
                        MembershipReferenceType.ORGANIZATION,
                        GraviteeContext.getCurrentOrganization());
            } else {
                ref = new MembershipService.MembershipReference(
                        MembershipReferenceType.ENVIRONMENT,
                        GraviteeContext.getCurrentEnvironment());
            }
            membershipService.addRoleToMemberOnReference(
                    ref,
                    new MembershipService.MembershipMember(userId, null, MembershipMemberType.USER),
                    new MembershipService.MembershipRole(
                            RoleScope.valueOf(roleEntity.getScope().name()),
                            roleEntity.getName()));
        }
    }

    private void trace(String userId, boolean match, String condition) {
        if (LOGGER.isDebugEnabled()) {
            if (match) {
                LOGGER.debug("the expression {} match {} on user's info ", condition, userId);
            } else {
                LOGGER.debug("the expression {} didn't match {} on user's info ", condition, userId);
            }
        }
    }

    @Override
    public void updateUserRoles(String userId, List<String> roleIds) {
        // check if user exist
        this.findById(userId);

        Set<MembershipEntity> userMemberships = membershipService.getMembershipsByMember(MembershipMemberType.USER, userId).stream()
                .filter(membership -> membership.getReferenceType().equals(MembershipReferenceType.ENVIRONMENT) || membership.getReferenceType().equals(MembershipReferenceType.ORGANIZATION))
                .collect(Collectors.toSet());
        userMemberships.forEach(membership -> {
            if (!roleIds.contains(membership.getRoleId())) {
                membershipService.deleteMembership(membership.getId());
            } else {
                roleIds.remove(membership.getRoleId());
            }
        });
        if (!roleIds.isEmpty()) {
            this.addRolesToUser(userId, roleIds.stream().map(roleService::findById).collect(Collectors.toSet()));
        }

    }
}
