package bindiego.io.elastic;

import java.io.Serializable;
import java.util.function.Predicate;
import org.apache.http.HttpEntity;

@FunctionalInterface
interface RetryPredicate extends Predicate<HttpEntity>, Serializable {}
