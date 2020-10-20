package bindiego.io.elastic;

import static org.apache.beam.vendor.guava.v26_0_jre.com.google.common.base.Preconditions.checkArgument;

import com.google.auto.value.AutoValue;
import java.io.Serializable;
import org.joda.time.Duration;

@AutoValue
public abstract class RetryConf implements Serializable {
  static final RetryPredicate DEFAULT_RETRY_PREDICATE = new DefaultRetryPredicate();

  abstract int getMaxAttempts();

  abstract Duration getMaxDuration();

  abstract RetryPredicate getRetryPredicate();

  abstract Builder builder();

  @AutoValue.Builder
  abstract static class Builder {
    abstract RetryConf.Builder setMaxAttempts(int maxAttempts);

    abstract RetryConf.Builder setMaxDuration(Duration maxDuration);

    abstract RetryConf.Builder setRetryPredicate(
        RetryPredicate retryPredicate);

    abstract RetryConf build();
  }

  public static RetryConf create(int maxAttempts, Duration maxDuration) {
    checkArgument(maxAttempts > 0, "maxAttempts must be greater than 0");
    checkArgument(
        maxDuration != null && maxDuration.isLongerThan(Duration.ZERO),
        "maxDuration must be greater than 0");

    return new AutoValue_RetryConf.Builder()
        .setMaxAttempts(maxAttempts)
        .setMaxDuration(maxDuration)
        .setRetryPredicate(DEFAULT_RETRY_PREDICATE)
        .build();
  }

  RetryConf withRetryPredicate(RetryPredicate predicate) {
    checkArgument(predicate != null, "predicate must be provided");

    return builder().setRetryPredicate(predicate).build();
  }
}
