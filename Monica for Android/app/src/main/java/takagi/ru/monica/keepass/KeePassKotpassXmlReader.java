package takagi.ru.monica.keepass;

import app.keemobile.kotpass.models.DeletedObject;
import app.keemobile.kotpass.models.Group;
import app.keemobile.kotpass.models.Meta;
import app.keemobile.kotpass.models.XmlContext;
import app.keemobile.kotpass.xml.DeletedObjectKt;
import app.keemobile.kotpass.xml.GroupKt;
import app.keemobile.kotpass.xml.MetaKt;
import org.redundent.kotlin.xml.Node;

/**
 * Bridge to the public JVM model readers in pinned kotpass 0.10.0.
 * Java avoids unsupported Kotlin INVISIBLE_REFERENCE compiler suppression.
 * KDBX 3/4 round-trip tests cover this boundary when the dependency changes.
 */
final class KeePassKotpassXmlReader {
    private KeePassKotpassXmlReader() {}

    static Meta readMeta(Node node) {
        return MetaKt.unmarshalMeta(node);
    }

    static Group readGroup(XmlContext.Decode context, Node node) {
        return GroupKt.unmarshalGroup(context, node);
    }

    static DeletedObject readDeletedObject(Node node) {
        return DeletedObjectKt.unmarshalDeletedObject(node);
    }
}
