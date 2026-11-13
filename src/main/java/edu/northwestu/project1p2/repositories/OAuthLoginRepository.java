package edu.northwestu.project1p2.repositories;

import edu.northwestu.project1p2.entities.OAuthLogin;
import java.util.Optional;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OAuthLoginRepository extends CrudRepository<OAuthLogin, Long> {

    Optional<OAuthLogin> findByClientIdAndProviderUserId(Long clientId, String providerUserId);
}
