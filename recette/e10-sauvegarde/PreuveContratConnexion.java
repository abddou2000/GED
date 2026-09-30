import com.fasterxml.jackson.databind.ObjectMapper;
import com.ipt.ged.identite.dto.DemandeConnexion;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Preuve, sans démarrer l'application, de ce que le back-end LIVRÉ fait du corps de
 * connexion envoyé par deploiement/scripts/test-fumee.sh (T-092) : désérialisation
 * par Jackson configuré comme Spring Boot (Jackson2ObjectMapperBuilder : propriétés
 * inconnues ignorées), puis validation Bean Validation de {@link DemandeConnexion}
 * — exactement ce que fait {@code @Valid @RequestBody} dans AuthController.
 *
 * <p>Usage : java -cp <classpath backend>:<backend/target/classes> PreuveContratConnexion.java [corps]
 * — tour 2 : {@code corps} est le corps réellement produit par le filtre jq du test-fumee.sh livré
 * (extrait par recette-t092.sh) ; sans argument, le corps {email, motDePasse} de la vague 8.
 */
public class PreuveContratConnexion {
    public static void main(String[] args) throws Exception {
        ObjectMapper jackson = Jackson2ObjectMapperBuilder.json().build();
        Validator validateur = Validation.buildDefaultValidatorFactory().getValidator();
        String[][] cas = {
                args.length > 0 ? new String[]{"test-fumee.sh livré (corps produit par son filtre jq)", args[0]}
                        : new String[]{"test-fumee.sh (jq '{email: $e, motDePasse: $m}')", "{\"email\":\"svc-fumee\",\"motDePasse\":\"secret\"}"},
                {"client de recette ClientGed.java / front Angular", "{\"identifiant\":\"svc-fumee\",\"motDePasse\":\"secret\"}"},
        };
        for (String[] c : cas) {
            DemandeConnexion d = jackson.readValue(c[1], DemandeConnexion.class);
            Set<ConstraintViolation<DemandeConnexion>> v = validateur.validate(d);
            String verdict = v.isEmpty() ? "ACCEPTÉ (200 si l'annuaire valide le compte)"
                    : "REFUSÉ 400 : " + v.stream().map(x -> x.getPropertyPath() + " « " + x.getMessage() + " »")
                    .sorted().collect(Collectors.joining(", "));
            System.out.println("CONTRAT|" + c[0] + "|" + d + "|" + verdict);
        }
    }
}
