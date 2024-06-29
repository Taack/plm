package plm

import crew.CrewController
import org.codehaus.groovy.runtime.MethodClosure
import taack.app.TaackApp
import taack.app.TaackAppRegisterService
import taack.render.TaackUiEnablerService

import javax.annotation.PostConstruct

class PlmFreeCadSecurityService {
    static lazyInit = false

    @PostConstruct
    void init() {
        TaackAppRegisterService.register(new TaackApp(PlmController.&index as MethodClosure, new String(this.class.getResourceAsStream("/plm/plm.svg").readAllBytes())))
    }
}
