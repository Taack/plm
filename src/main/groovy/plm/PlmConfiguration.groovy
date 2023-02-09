package plm

import groovy.transform.CompileStatic
import io.micronaut.context.annotation.ConfigurationProperties

@CompileStatic
@ConfigurationProperties('plm')
class PlmConfiguration {
    String freecadPath
}
