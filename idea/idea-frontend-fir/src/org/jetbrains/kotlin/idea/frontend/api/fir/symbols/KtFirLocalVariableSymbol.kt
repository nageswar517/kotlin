/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.frontend.api.fir.symbols

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.idea.fir.findPsi
import org.jetbrains.kotlin.idea.frontend.api.ValidityOwner
import org.jetbrains.kotlin.idea.frontend.api.types.KtType
import org.jetbrains.kotlin.idea.frontend.api.fir.KtSymbolByFirBuilder
import org.jetbrains.kotlin.idea.frontend.api.fir.utils.cached
import org.jetbrains.kotlin.idea.frontend.api.fir.utils.weakRef
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtLocalVariableSymbol
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtSymbolKind
import org.jetbrains.kotlin.idea.frontend.api.withValidityAssertion
import org.jetbrains.kotlin.name.Name

internal class KtFirLocalVariableSymbol(
    fir: FirProperty,
    override val token: ValidityOwner,
    private val builder: KtSymbolByFirBuilder
) : KtLocalVariableSymbol(),
    KtFirSymbol<FirProperty> {
    init {
        assert(fir.isLocal)
    }

    override val fir: FirProperty by weakRef(fir)
    override val psi: PsiElement? by cached { fir.findPsi(fir.session) }

    override val isVal: Boolean get() = withValidityAssertion { fir.isVal }
    override val name: Name get() = withValidityAssertion { fir.name }
    override val type: KtType by cached { builder.buildKtType(fir.returnTypeRef) }
    override val symbolKind: KtSymbolKind get() = KtSymbolKind.LOCAL
}