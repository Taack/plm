package plm

import jakarta.annotation.PostConstruct
import org.codehaus.groovy.runtime.MethodClosure
import taack.app.TaackApp
import taack.app.TaackAppRegisterService

class PlmFreeCadSecurityService {
    static lazyInit = false

    @PostConstruct
    void init() {
        TaackAppRegisterService.register(new TaackApp(PlmController.&index as MethodClosure, new String(this.class.getResourceAsStream("/plm/plm.svg").readAllBytes())))
    }
}
