package app.keemobile.kotpass.database.header

import app.keemobile.kotpass.constants.VariantTypeId
import okio.ByteString

sealed interface VariantItem {
    val typeId: Int

    @JvmInline
    value class UInt32(val value: UInt) : VariantItem {
        override val typeId: Int get() = VariantTypeId.UInt32
    }

    @JvmInline
    value class UInt64(val value: ULong) : VariantItem {
        override val typeId: Int get() = VariantTypeId.UInt64
    }

    @JvmInline
    value class Bool(val value: Boolean) : VariantItem {
        override val typeId: Int get() = VariantTypeId.Bool
    }

    @JvmInline
    value class Int32(val value: Int) : VariantItem {
        override val typeId: Int get() = VariantTypeId.Int32
    }

    @JvmInline
    value class Int64(val value: Long) : VariantItem {
        override val typeId: Int get() = VariantTypeId.Int64
    }

    @JvmInline
    value class StringUtf8(val value: String) : VariantItem {
        override val typeId: Int get() = VariantTypeId.StringUtf8
    }

    @JvmInline
    value class Bytes(val value: ByteString) : VariantItem {
        override val typeId: Int get() = VariantTypeId.Bytes
    }
}
