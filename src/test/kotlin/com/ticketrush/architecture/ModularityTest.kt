package com.ticketrush.architecture

import com.ticketrush.TicketRushApplication
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.docs.Documenter

class ModularityTest {
    private val modules = ApplicationModules.of(TicketRushApplication::class.java)

    @Test
    fun `모듈 경계를 위반하지 않는다`() {
        modules.verify()
    }

    @Test
    fun `모듈 문서를 생성한다`() {
        Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml()
    }
}
