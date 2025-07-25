package fr.polytech.melusine.services;

import fr.polytech.melusine.exceptions.BadRequestException;
import fr.polytech.melusine.exceptions.ConflictException;
import fr.polytech.melusine.exceptions.NotFoundException;
import fr.polytech.melusine.exceptions.errors.AccountError;
import fr.polytech.melusine.exceptions.errors.CreditError;
import fr.polytech.melusine.exceptions.errors.UserError;
import fr.polytech.melusine.mappers.UserMapper;
import fr.polytech.melusine.models.dtos.requests.AccountRequest;
import fr.polytech.melusine.models.dtos.requests.UserRegistrationRequest;
import fr.polytech.melusine.models.dtos.requests.UserUpdateRequest;
import fr.polytech.melusine.models.dtos.responses.UserResponse;
import fr.polytech.melusine.models.entities.Account;
import fr.polytech.melusine.models.entities.Order;
import fr.polytech.melusine.models.entities.User;
import fr.polytech.melusine.repositories.AccountRepository;
import fr.polytech.melusine.repositories.OrderItemRepository;
import fr.polytech.melusine.repositories.OrderRepository;
import fr.polytech.melusine.repositories.UserRepository;
import io.jsonwebtoken.lang.Strings;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.authc.credential.PasswordService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

import static fr.polytech.melusine.utils.AuthenticatedFinder.ensureAuthenticatedUserIsAdmin;
import static fr.polytech.melusine.utils.AuthenticatedFinder.getAuthenticatedUser;
import static fr.polytech.melusine.utils.MoneyFormatter.formatToLong;

@Slf4j
@Service
public class UserService {

    private UserRepository userRepository;
    private AccountRepository accountRepository;
    private PasswordService passwordService;
    private UserMapper userMapper;
    private OrderRepository orderRepository;
    private OrderItemRepository orderItemRepository;
    private Clock clock;


    public UserService(UserRepository userRepository, AccountRepository accountRepository, PasswordService passwordService,
                       UserMapper userMapper, OrderRepository orderRepository, OrderItemRepository orderItemRepository, Clock clock) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.passwordService = passwordService;
        this.userMapper = userMapper;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.clock = clock;
    }

    public UserResponse createUser(UserRegistrationRequest userRegistrationRequest) {
        log.debug("Creation of user with last name: " + userRegistrationRequest.getLastName() +
                " first name: " + userRegistrationRequest.getFirstName());
        ensureCreditUpperThanZero(formatToLong(userRegistrationRequest.getCredit()));
        String firstName = Strings.capitalize(userRegistrationRequest.getFirstName().toLowerCase().trim());
        String lastName = Strings.capitalize(userRegistrationRequest.getLastName().toLowerCase().trim());
        String nickName = null;
        if (Objects.nonNull(userRegistrationRequest.getNickName())) {
            nickName = Strings.capitalize(userRegistrationRequest.getNickName().toLowerCase().trim());
        }
        if (userRepository.existsByFirstNameAndLastNameAndSection(firstName, lastName, userRegistrationRequest.getSection()))
            throw new ConflictException(UserError.CONFLICT, firstName, lastName, userRegistrationRequest.getSection());
        long requestedCredit = formatToLong(userRegistrationRequest.getCredit());
        long credit = userRegistrationRequest.isMembership() ? requestedCredit + getMembershipBonus(requestedCredit) : requestedCredit ;
        User user = User.builder()
                .firstName(firstName)
                .lastName(lastName)
                .nickName(nickName)
                .section(userRegistrationRequest.getSection())
                .credit(credit)
                .isMembership(userRegistrationRequest.isMembership())
                .createdAt(OffsetDateTime.now(clock))
                .updatedAt(OffsetDateTime.now(clock))
                .build();

        User savedUser = userRepository.save(user);
        boolean isBarman = false;
        AccountRequest accountRequest = userRegistrationRequest.getAccount();
        if (getAuthenticatedUser().isAdmin() && Objects.nonNull(accountRequest)) {
            String encryptedPassword = passwordService.encryptPassword(accountRequest.getPassword().trim());
            String email = accountRequest.getEmail().trim().toLowerCase();

            if (accountRepository.existsByEmail(email))
                throw new ConflictException(AccountError.CONFLICT_EMAIL, email);
            isBarman = accountRequest.isBarman();
            Account account = Account.builder()
                    .password(encryptedPassword)
                    .email(email)
                    .isBarman(isBarman)
                    .user(savedUser)
                    .createdAt(OffsetDateTime.now(clock))
                    .updatedAt(OffsetDateTime.now(clock))
                    .build();
            accountRepository.save(account);
        }
        log.info("End of the creation of a user");

        return userMapper.mapToUserResponse(savedUser, null, isBarman);
    }

    private void ensureCreditUpperThanZero(Long credit) {
        if (credit <= 0) throw new BadRequestException(CreditError.INVALID_CREDIT, credit);
    }

    /**
     * Get all users by page.
     *
     * @param pageable the page
     * @return a page object of user response
     */
    public Page<UserResponse> getUsers(Pageable pageable) {
        log.debug("Find accounts order by last name");
        Page<User> userPages = userRepository.findAll(pageable);
        return userPages.map(this::getUserResponse);
    }

    private UserResponse getUserResponse(User user) {
        Account account = accountRepository.findByUser(user)
                .orElse(null);
        String email = Objects.nonNull(account) ? account.getEmail() : null;
        boolean isBarman = Objects.nonNull(account) ? account.isBarman() : false;

        return userMapper.mapToUserResponse(user, email, isBarman);
    }

    public UserResponse creditUser(String userId, UserUpdateRequest request) {
        log.debug("Credit a user with ID : " + userId + " and amount : " + request.getCredit());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException(UserError.NOT_FOUND, userId));

        long requestedCredit = formatToLong(request.getCredit());
        long newCredit;

        if (user.isMembership()) {
            newCredit = user.getCredit() + requestedCredit + getMembershipBonus(requestedCredit);
        } else {
            newCredit = user.getCredit() + requestedCredit;
        }

        User updatedUser = userRepository.save(user.toBuilder()
                .credit(newCredit)
                .updatedAt(OffsetDateTime.now(clock))
                .build()
        );

        log.info("End of credit a user");
        return getUserResponse(updatedUser);
    }

    private long getMembershipBonus(long requestedCredit) {
        return (requestedCredit * 10) / 100;
    }

    public Page<UserResponse> searchUser(String name, Pageable pageable) {
        log.debug("Search user by this char : " + name);
        String formattedName = name.toLowerCase().trim();
        Page<User> users = userRepository.findAllByFirstNameIgnoreCaseContainingOrLastNameIgnoreCaseContainingOrNickNameIgnoreCaseContaining(
                pageable,
                formattedName,
                formattedName,
                formattedName
        );
        return users.map(this::getUserResponse);
    }

    public UserResponse updateUser(String id, UserUpdateRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(UserError.NOT_FOUND, id));
        String firstName = Strings.capitalize(request.getFirstName().toLowerCase().trim());
        String lastName = Strings.capitalize(request.getLastName().toLowerCase().trim());
        String nickName = Strings.capitalize(request.getNickName().toLowerCase().trim());
        User updatedUser = user.toBuilder()
                .firstName(firstName)
                .lastName(lastName)
                .nickName(nickName)
                .section(request.getSection())
                .isMembership(Objects.nonNull(request.getIsMembership()) ? request.getIsMembership() : user.isMembership() )
                .build();

        User savedUser = userRepository.save(updatedUser);
        return getUserResponse(savedUser);
    }

    @Transactional
    public void deleteUser(String id) {
        ensureAuthenticatedUserIsAdmin();
        log.info("Deletion of order for user with ID: " + id);
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException(UserError.NOT_FOUND, id));
        log.info("Deletion of user with ID:" + id);
        List<Order> orders = orderRepository.findAllByUser(user);
        orders.forEach(order -> orderItemRepository.deleteByOrder(order));
        orderRepository.deleteByUser(user);
        accountRepository.deleteByUser(user);
        userRepository.deleteById(user.getId());
    }

}
