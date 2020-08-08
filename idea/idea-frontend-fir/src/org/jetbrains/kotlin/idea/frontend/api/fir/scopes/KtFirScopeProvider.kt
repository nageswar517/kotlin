/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.frontend.api.fir.scopes

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.scope
import org.jetbrains.kotlin.fir.scopes.FirContainingNamesAwareScope
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.impl.FirAbstractSimpleImportingScope
import org.jetbrains.kotlin.fir.scopes.impl.FirAbstractStarImportingScope
import org.jetbrains.kotlin.fir.scopes.impl.FirPackageMemberScope
import org.jetbrains.kotlin.fir.scopes.impl.declaredMemberScope
import org.jetbrains.kotlin.fir.scopes.unsubstitutedScope
import org.jetbrains.kotlin.idea.fir.getOrBuildFirOfType
import org.jetbrains.kotlin.idea.fir.low.level.api.FirModuleResolveState
import org.jetbrains.kotlin.idea.fir.low.level.api.LowLevelFirApiFacade
import org.jetbrains.kotlin.idea.frontend.api.ValidityToken
import org.jetbrains.kotlin.idea.frontend.api.ValidityTokenOwner
import org.jetbrains.kotlin.idea.frontend.api.fir.FirScopeRegistry
import org.jetbrains.kotlin.idea.frontend.api.fir.KtSymbolByFirBuilder
import org.jetbrains.kotlin.idea.frontend.api.fir.symbols.KtFirClassOrObjectSymbol
import org.jetbrains.kotlin.idea.frontend.api.fir.types.KtFirType
import org.jetbrains.kotlin.idea.frontend.api.fir.utils.weakRef
import org.jetbrains.kotlin.idea.frontend.api.scopes.*
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtClassOrObjectSymbol
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtPackageSymbol
import org.jetbrains.kotlin.idea.frontend.api.types.KtType
import org.jetbrains.kotlin.idea.frontend.api.withValidityAssertion
import org.jetbrains.kotlin.idea.util.getElementTextInContext
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import java.util.*

internal class KtFirScopeProvider(
    override val token: ValidityToken,
    private val builder: KtSymbolByFirBuilder,
    private val project: Project,
    session: FirSession,
    firResolveState: FirModuleResolveState,
    firScopeRegistry: FirScopeRegistry,
) : KtScopeProvider(), ValidityTokenOwner {
    private val session by weakRef(session)
    private val firResolveState by weakRef(firResolveState)
    private val firScopeStorage by weakRef(firScopeRegistry)

    private val memberScopeCache = IdentityHashMap<KtClassOrObjectSymbol, KtMemberScope>()
    private val declaredMemberScopeCache = IdentityHashMap<KtClassOrObjectSymbol, KtDeclaredMemberScope>()
    private val packageMemberScopeCache = IdentityHashMap<KtPackageSymbol, KtPackageScope>()

    override fun getMemberScope(classSymbol: KtClassOrObjectSymbol): KtMemberScope = withValidityAssertion {
        memberScopeCache.getOrPut(classSymbol) {
            check(classSymbol is KtFirClassOrObjectSymbol)
            val firScope = classSymbol.fir.unsubstitutedScope(classSymbol.fir.session, ScopeSession()).also(firScopeStorage::register)
            KtFirMemberScope(classSymbol, firScope, token, builder)
        }
    }

    override fun getDeclaredMemberScope(classSymbol: KtClassOrObjectSymbol): KtDeclaredMemberScope = withValidityAssertion {
        declaredMemberScopeCache.getOrPut(classSymbol) {
            check(classSymbol is KtFirClassOrObjectSymbol)
            val firScope = declaredMemberScope(classSymbol.fir).also(firScopeStorage::register)
            KtFirDeclaredMemberScope(classSymbol, firScope, token, builder)
        }
    }

    override fun getPackageScope(packageSymbol: KtPackageSymbol): KtPackageScope = withValidityAssertion {
        packageMemberScopeCache.getOrPut(packageSymbol) {
            val firPackageScope = FirPackageMemberScope(packageSymbol.fqName, session/*TODO use correct session here*/)
                .also(firScopeStorage::register)
            KtFirPackageScope(firPackageScope, builder, token)
        }
    }

    override fun getCompositeScope(subScopes: List<KtScope>): KtCompositeScope = withValidityAssertion {
        KtFirCompositeScope(subScopes, token)
    }

    override fun getScopeForType(type: KtType): KtScope? {
        check(type is KtFirType) { "KtFirScopePriovider can only work with KtFirType, but ${type::class} was provided" }

        val firTypeScope = type.coneType.scope(session, ScopeSession()) ?: return null
        return convertToKtScope(firTypeScope)
    }

    override fun getScopeContextForPosition(originalFile: KtFile, positionInFakeFile: KtElement): KtScopeContext = withValidityAssertion {
        val originalFirFile = originalFile.getOrBuildFirOfType<FirFile>(firResolveState)
        val fakeEnclosingFunction = positionInFakeFile.getNonStrictParentOfType<KtNamedFunction>()
            ?: error("Cannot find enclosing function for ${positionInFakeFile.getElementTextInContext()}")

        val completionContext = LowLevelFirApiFacade.buildCompletionContextForFunction(
            originalFirFile,
            fakeEnclosingFunction,
            state = firResolveState
        )

        val towerDataContext = completionContext.getTowerDataContext(positionInFakeFile)

        val implicitReceivers = towerDataContext.nonLocalTowerDataElements.mapNotNull { it.implicitReceiver }.distinct()
        val implicitReceiversTypes = implicitReceivers.map { builder.buildKtType(it.type) }

        val implicitReceiverScopes = implicitReceivers.mapNotNull { it.implicitScope }
        val nonLocalScopes = towerDataContext.nonLocalTowerDataElements.mapNotNull { it.scope }.distinct()
        val firLocalScopes = towerDataContext.localScopes

        @OptIn(ExperimentalStdlibApi::class)
        val allKtScopes = buildList {
            implicitReceiverScopes.mapTo(this, ::convertToKtScope)
            nonLocalScopes.mapTo(this, ::convertToKtScope)
            firLocalScopes.mapTo(this, ::convertToKtScope)
        }

        KtScopeContext(getCompositeScope(allKtScopes), implicitReceiversTypes)
    }

    private fun convertToKtScope(firScope: FirScope): KtScope {
        firScopeStorage.register(firScope)
        return when (firScope) {
            is FirAbstractSimpleImportingScope -> KtFirNonStarImportingScope(firScope, builder, token)
            is FirAbstractStarImportingScope -> KtFirStarImportingScope(firScope, builder, project, token)
            is FirPackageMemberScope -> KtFirPackageScope(firScope, builder, token)
            is FirContainingNamesAwareScope -> KtFirDelegatingScopeImpl(firScope, builder, token)
            else -> TODO(firScope::class.toString())
        }
    }
}

private class KtFirDelegatingScopeImpl<S>(
    firScope: S, builder: KtSymbolByFirBuilder,
    token: ValidityToken
) : KtFirDelegatingScope<S>(builder, token), ValidityTokenOwner where S : FirContainingNamesAwareScope, S : FirScope {
    override val firScope: S by weakRef(firScope)
}
