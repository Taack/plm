package plm


import grails.compiler.GrailsCompileStatic
import groovy.transform.CompileStatic
import org.taack.Attachment
import org.taack.User
import taack.ast.annotation.TaackFieldEnum
import taack.domain.IDomainHistory
import taack.domain.IEnumTransition2

@CompileStatic
enum PlmFreeCadPartStatus implements IEnumTransition2<User>  {
    CREATED, FREE, LOCKED, FREEZE, OBSOLETE

    @Override
    IEnumTransition2[] transitionsTo(User user) {
        switch (this) {
            case CREATED:
                return [FREE, LOCKED, FREEZE, OBSOLETE] as IEnumTransition2[]
            case LOCKED:
                return [FREE, FREEZE] as IEnumTransition2[]
            case FREEZE:
                return [FREE, LOCKED] as IEnumTransition2[]
            case OBSOLETE:
                return [FREE, FREEZE, LOCKED] as IEnumTransition2[]
        }
    }

    @Override
    List<String> getLockedFields(User user) {
        return null
    }
}

@GrailsCompileStatic
@TaackFieldEnum
class PlmFreeCadPart implements IDomainHistory<PlmFreeCadPart> {
    Date dateCreated
    Date lastUpdated

    Long computedVersion = 0

    User userCreated
    User userUpdated

    User lockedBy

    Date plmFileDateCreated
    Date plmFileLastUpdated
    String plmFileUserUpdated
    String plmFileUserCreated
    String plmFilePath
    String plmContentType
    String plmContentShaOne
    String originalName
    String label
    String pathOnHost
    String fileId

    String comment
    String commentVersion

    Set<PlmFreeCadLink> plmLinks

    Long creationOrder = Long.MAX_VALUE
    Boolean active = true
    PlmFreeCadPart nextVersion
    Long cTimeNs
    Long mTimeNs

    PlmFreeCadPartStatus status = PlmFreeCadPartStatus.CREATED

    static constraints = {
        lockedBy nullable: true
        plmFileLastUpdated nullable: true
        plmFileUserCreated nullable: true
        comment nullable: true
        commentVersion nullable: true, widget: "markdown"
        nextVersion nullable: true
        plmContentType nullable: true
        plmContentShaOne nullable: true
        originalName nullable: true
        pathOnHost nullable: true
    }

    static hasMany = [
            plmLinks: PlmFreeCadLink,
            plmLinksOld: PlmFreeCadLink,
            attachments  : Attachment,
    ]

    static mapping = {
        version false
        comment type: 'text'
        commentVersion type: 'text'
        computedVersion column: '`version`'
    }

    static mappedBy = [plmLinks: "parentPart", plmLinksOld: "none"]

    @Override
    PlmFreeCadPart cloneDirectObjectData() {
        if (this.id) {
            PlmFreeCadPart oldPart = new PlmFreeCadPart()
            oldPart.userCreated = userUpdated
            log.info "PlmFreeCadPart::cloneDirectObjectData ${computedVersion} ${userCreated}: ${dateCreated}, ${userUpdated}: ${lastUpdated}"
            oldPart.creationOrder = computedVersion++
            oldPart.active = false
            oldPart.nextVersion = this
            oldPart.lockedBy = lockedBy
            oldPart.plmFileDateCreated = plmFileDateCreated
            oldPart.plmFileLastUpdated = plmFileLastUpdated
            oldPart.plmFileUserUpdated = plmFileUserUpdated
            oldPart.plmFileUserCreated = plmFileUserCreated
            oldPart.cTimeNs = cTimeNs
            oldPart.mTimeNs = mTimeNs
            oldPart.plmFilePath = plmFilePath
            oldPart.plmContentType = plmContentType
            oldPart.plmContentShaOne = plmContentShaOne
            oldPart.originalName = originalName
            oldPart.pathOnHost = pathOnHost
            oldPart.fileId = fileId
            oldPart.comment = comment
            oldPart.label = label
            oldPart.commentVersion = commentVersion
            oldPart.status = status
            plmLinks.each {
                oldPart.addToPlmLinks(it)
            }
            return oldPart
        }
        return null
    }

    @Override
    List<PlmFreeCadPart> getHistory() {
        try {
            return (PlmFreeCadPart.findAllByNextVersion(this) + this).sort {
                (it as PlmFreeCadPart).creationOrder
            }
        } catch(e) {
            println "PlmFreeCadPart ${e}"
            return null
        }
    }

    PlmFreeCadPart getPreviousVersion() {
        if (creationOrder == 1) return nextVersion
        else {
            def t = PlmFreeCadPart.find("from PlmFreeCadPart t where creationOrder < :thisCreationOrder and nextVersion.id = :thisNextVersionId order by creationOrder desc", [thisCreationOrder: creationOrder, thisNextVersionId: nextVersion?.id ?: id])
            if (t) return t
            else return nextVersion
        }
    }

    User getHistoryUserCreated() {
        if (!nextVersion) userUpdated
        else userCreated
    }

    Date getHistoryDateCreated() {
        if (!nextVersion) lastUpdated
        else {
            previousVersion?.dateCreated
        }
    }

    private Collection<PlmFreeCadPart> linkedPartsFromParts(Collection<PlmFreeCadPart> parts) {
        Set ret = []
        ret.addAll(parts)
        parts.each {
           ret.addAll(linkedPartsFromParts(it.linkedParts))
        }
        ret
    }

    Collection<PlmFreeCadPart> getLinkedParts() {
        this.plmLinks.collect {
            it.part.history[it.partLinkVersion]
        }
    }

    Collection<PlmFreeCadPart> getAllLinkedParts() {
        linkedPartsFromParts([this])
    }
}
