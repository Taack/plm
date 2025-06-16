package plm


import grails.compiler.GrailsCompileStatic
import jakarta.annotation.PostConstruct
import org.codehaus.groovy.runtime.MethodClosure as MC
import org.grails.datastore.gorm.GormEntity
import taack.domain.TaackSearchService
import taack.solr.SolrFieldType
import taack.solr.SolrSpecifier
import taack.ui.dsl.UiBlockSpecifier

@GrailsCompileStatic
class PlmSearchService implements TaackSearchService.IIndexService {

    static lazyInit = false

    TaackSearchService taackSearchService

    @PostConstruct
    private void init() {
        taackSearchService.registerSolrSpecifier(this, new SolrSpecifier(PlmFreeCadPart, PlmController.&showPart as MC, this.&labeling as MC, { PlmFreeCadPart part ->
            part ?= new PlmFreeCadPart()
            indexField SolrFieldType.TXT_NO_ACCENT, part.comment_
            indexField SolrFieldType.DATE, 0.5f, true, part.dateCreated_
            indexField SolrFieldType.POINT_STRING, "userCreated", 0.5f, true, part.userCreated?.username
        }))
    }

    String labeling(Long id) {
        def p = PlmFreeCadPart.read(id)
        "PlmFreeCadPart: ${p.label} ($id)"
    }

    @Override
    List<? extends GormEntity> indexThose(Class<? extends GormEntity> toIndex) {
        if (toIndex.isAssignableFrom(PlmFreeCadPart)) return PlmFreeCadPart.list()
        else null
    }

    UiBlockSpecifier buildSearchBlock(String q) {
        taackSearchService.search(q, PlmController.&search as MC, PlmFreeCadPart)
    }
}
