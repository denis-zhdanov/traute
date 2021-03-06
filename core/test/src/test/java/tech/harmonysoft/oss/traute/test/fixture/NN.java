package tech.harmonysoft.oss.traute.test.fixture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER, ElementType.PACKAGE, ElementType.TYPE})
public @interface NN {
}
