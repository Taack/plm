package plm


import crew.User
import grails.compiler.GrailsCompileStatic
import grails.converters.JSON
import grails.plugin.springsecurity.SpringSecurityService
import grails.plugin.springsecurity.annotation.Secured
import grails.util.Pair
import org.springframework.beans.factory.annotation.Value
import taack.domain.TaackFilter
import taack.domain.TaackFilterService

@GrailsCompileStatic
@Secured(["ROLE_PLM_USER", "ROLE_ADMIN"])
class PlmJsonController {

    PlmFreeCadSecurityService plmFreeCadSecurityService
    SpringSecurityService springSecurityService
    TaackFilterService taackFilterService

    @Value('${grails.controllers.upload.maxFileSize}')
    Long maximumFileUploadSize

    @Value('${grails.controllers.upload.maxRequestSize}')
    Long maximumRequestSize

    private List<Map<String, Object>> prepareParts(List<PlmFreeCadPart> parts, User user) {
        parts.grep { PlmFreeCadPart part ->
            plmFreeCadSecurityService.canDownloadFile(part, user)
        }.collect { PlmFreeCadPart part ->
            [
                    id                : part.id,
                    label             : part.label,
                    userCreated       : part.userCreated.username,
                    status            : part.status,
                    computedVersion   : part.computedVersion,
                    pathOnHost        : part.pathOnHost,
                    plmFileLastUpdated: part.plmFileLastUpdated
            ] as Map<String, Object>
        }
    }

    private String getServerBuildDate() {
        Properties properties = new Properties()

        InputStream input = this.class.classLoader
                .getResourceAsStream("build-info.properties")

        if (input) {
            try {
                properties.load(input)
            } finally {
                input.close()
            }
        }
        String buildDate = properties.getProperty("server.build.date", "unknown")

        if (buildDate != "unknown") {
            return buildDate.split(" ")[0]
        }

        return buildDate
    }

    def queryModel() {
        PlmFreeCadPart part = new PlmFreeCadPart()
        Pair<List<PlmFreeCadPart>, Long> parts = taackFilterService.getBuilder(PlmFreeCadPart)
                .setSortOrder(TaackFilter.Order.ASC, part.label_)
                .build().list() as Pair<List<PlmFreeCadPart>, Long>
        response.contentType = 'application/json'
        render prepareParts(parts.aValue, springSecurityService.currentUser as User) as JSON
    }

    // Endpoint: /plmJson/serverInfo
    def serverInfo() {

        Map<String, Object> result = [
                serverBuild             : getServerBuildDate(),
                messagingProtocolVersion: "2",
                maximumFileUploadSize   : maximumFileUploadSize,
                maxRequestSize          : maximumRequestSize
        ] as Map<String, Object>

        response.contentType = 'application/json'
        render result as JSON
    }
}
