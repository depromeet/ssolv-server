package org.depromeet.team3.surveycategory

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class SurveyCategoryInitializer(private val surveyCategoryJpaRepository: SurveyCategoryJpaRepository) : ApplicationRunner {

    @Transactional
    override fun run(args: ApplicationArguments?) {
        if (surveyCategoryJpaRepository.count() > 0) return

        // 1. BRANCH 카테고리 생성
        val korean = saveBranch("한식", 1)
        val chinese = saveBranch("중식", 2)
        val western = saveBranch("양식", 3)
        val japanese = saveBranch("일식", 4)
        val asian = saveBranch("동남아 음식", 5)
        saveBranch("다 괜찮아요", 6)

        // 2. LEAF 카테고리 생성
        // 한식 (id: 1)
        saveLeaf("밥류", 1, korean)
        saveLeaf("구이·조림류", 2, korean)
        saveLeaf("국·탕·찌개류", 3, korean)
        saveLeaf("분식", 4, korean)
        saveLeaf("전·부침류", 5, korean)

        // 중식 (id: 4)
        saveLeaf("면류", 1, chinese)
        saveLeaf("밥류", 2, chinese)
        saveLeaf("튀김·볶음류", 3, chinese)
        saveLeaf("딤섬·만두류", 4, chinese)
        saveLeaf("국물요리", 5, chinese)

        // 양식 (id: 5)
        saveLeaf("파스타", 1, western)
        saveLeaf("스테이크", 2, western)
        saveLeaf("피자", 3, western)
        saveLeaf("샐러드·샌드위치", 4, western)
        saveLeaf("리조토", 5, western)

        // 일식 (id: 6)
        saveLeaf("초밥·사시미", 1, japanese)
        saveLeaf("밥류", 2, japanese)
        saveLeaf("면류", 3, japanese)
        saveLeaf("튀김·구이류", 4, japanese)
        saveLeaf("탕·나베류", 5, japanese)

        // 동남아 음식 (id: 7)
        saveLeaf("베트남 음식", 1, asian)
        saveLeaf("멕시코 음식", 2, asian)
        saveLeaf("인도 음식", 3, asian)
        saveLeaf("태국 음식", 4, asian)
    }

    private fun saveBranch(name: String, sortOrder: Int): SurveyCategoryEntity = surveyCategoryJpaRepository.save(
        SurveyCategoryEntity(
            name = name,
            sortOrder = sortOrder,
            level = SurveyCategoryLevel.BRANCH,
            parent = null,
        ),
    )

    private fun saveLeaf(name: String, sortOrder: Int, parent: SurveyCategoryEntity): SurveyCategoryEntity =
        surveyCategoryJpaRepository.save(
            SurveyCategoryEntity(
                name = name,
                sortOrder = sortOrder,
                level = SurveyCategoryLevel.LEAF,
                parent = parent,
            ),
        )
}
