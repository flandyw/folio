package com.folio.notes

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class VceSubjectTests {
    @Test fun everyStudyHasADefaultPaperAndColour() {
        VceSubject.entries.forEach { subject ->
            assertTrue(subject.label.isNotBlank())
            assertTrue(subject.color != 0L)
        }
    }

    @Test fun typedSubjectsMatchTheirStudy() {
        assertEquals(VceSubject.MATHS_METHODS, VceSubject.match("methods"))
        assertEquals(VceSubject.MATHS_METHODS, VceSubject.match("Maths Methods"))
        assertEquals(VceSubject.CHEMISTRY, VceSubject.match("Chemistry"))
        assertEquals(VceSubject.CHEMISTRY, VceSubject.match("chem"))
        assertEquals(VceSubject.SPECIALIST_MATHS, VceSubject.match("specialist"))
        assertNull(VceSubject.match(""))
        assertNull(VceSubject.match("underwater basket weaving"))
    }

    @Test fun unknownStudyNamesFallBackToNull() {
        assertNull(VceSubject.safeValueOf(" Latin "))
        assertNull(VceSubject.safeValueOf(null))
        assertEquals(VceSubject.PHYSICS, VceSubject.safeValueOf("PHYSICS"))
    }
}

class ExamTagsTests {
    @Test fun aFreshNotebookIsUntagged() {
        assertFalse(ExamTags().isTagged)
        assertEquals("", ExamTags().summaryLine())
        assertEquals("", ExamTags().subjectLabel)
    }

    @Test fun anyFilledFieldMarksTheTagsAsUsed() {
        assertTrue(ExamTags(subject = VceSubject.MATHS_METHODS).isTagged)
        assertTrue(ExamTags(year = 2022).isTagged)
        assertTrue(ExamTags(company = "VCAA").isTagged)
        assertTrue(ExamTags(type = ExamType.EXAM_1).isTagged)
        assertTrue(ExamTags(status = ExamStatus.MARKED).isTagged)
        assertTrue(ExamTags(tags = setOf(ExamTagType.HARD)).isTagged)
        assertTrue(ExamTags(difficulty = 2).isTagged)
        assertTrue(ExamTags(marksTotal = 40).isTagged)
        assertTrue(ExamTags(unit = 3).isTagged)
        assertTrue(ExamTags(examDate = 1L).isTagged)
    }

    @Test fun theSummaryLineReadsLikeAPaperLabel() {
        val tags = ExamTags(company = "VCAA", year = 2022, type = ExamType.EXAM_1, subject = VceSubject.MATHS_METHODS)
        assertEquals("VCAA · 2022 · Exam 1", tags.summaryLine())
    }

    @Test fun tagsRoundTripThroughJson() {
        val tags = ExamTags(
            subject = VceSubject.SPECIALIST_MATHS, year = 2021, company = "NEAP",
            type = ExamType.EXAM_2, unit = 4, difficulty = 3, marksTotal = 80,
            status = ExamStatus.REDONE, tags = setOf(ExamTagType.HARD, ExamTagType.REDO),
            examDate = 1_760_000_000_000
        )
        val decoded = ExamTagsCodec.decode(ExamTagsCodec.encode(tags))
        assertEquals(tags, decoded)
    }

    @Test fun aBlankTagBlockDecodesWithDefaults() {
        assertEquals(ExamTags(), ExamTagsCodec.decode(null))
        assertEquals(ExamTags(), ExamTagsCodec.decode(org.json.JSONObject("{}")))
        // Unknown enum names fall back instead of throwing.
        val json = org.json.JSONObject().put("subject", "Latin").put("status", "FINISHED")
        val decoded = ExamTagsCodec.decode(json)
        assertNull(decoded.subject)
        assertEquals(ExamStatus.TO_DO, decoded.status)
    }

    @Test fun aNotebookCodecCarriesExamFieldsThroughThePortableFormat() {
        val note = Notebook(
            title = "2022 Exam 1", exam = ExamTags(subject = VceSubject.MATHS_METHODS, year = 2022, company = "VCAA"),
            setId = "set-9", attempts = listOf(ExamAttempt(id = "a1", score = 32, total = 40, secondsTaken = 5100, timed = true)),
            pages = listOf(NotePage(paper = Paper.MC_SHEET, redoFlag = true))
        )
        val decoded = NoteCodec.decode(NoteCodec.encode(note))
        assertEquals(note.exam, decoded.exam)
        assertEquals("set-9", decoded.setId)
        assertEquals(note.attempts, decoded.attempts)
        assertTrue(decoded.pages.first().redoFlag)
        assertEquals(Paper.MC_SHEET, decoded.pages.first().paper)
    }
}

class ExamAttemptTests {
    @Test fun theShareIsScoreOverTotal() {
        assertEquals(.8f, ExamAttempt(score = 32, total = 40).share!!, .0001f)
        assertEquals(1f, ExamAttempt(score = 45, total = 40).share!!, .0001f)
        assertEquals(0f, ExamAttempt(score = 0, total = 40).share!!, .0001f)
    }

    @Test fun anUnknownTotalHasNoShare() {
        assertNull(ExamAttempt(score = 32).share)
        assertNull(ExamAttempt(score = 32, total = 0).share)
        assertNull(ExamAttempt(score = 32, total = -5).share)
    }

    @Test fun aNewAttemptAppendsAfterTheExistingOnes() {
        val first = ExamAttempt(id = "a", score = 20, total = 40)
        val second = ExamAttempt(id = "b", score = 30, total = 40)
        val note = Notebook(title = "Exam", attempts = listOf(first)).withAttempt(second)
        assertEquals(listOf(first, second), note.attempts)
    }

    @Test fun replacingAnAttemptKeepsItsPlace() {
        val first = ExamAttempt(id = "a", score = 20, total = 40)
        val second = ExamAttempt(id = "b", score = 30, total = 40)
        val edited = first.copy(score = 25)
        val note = Notebook(title = "Exam", attempts = listOf(first, second)).withAttempt(edited)
        assertEquals(listOf(edited, second), note.attempts)
    }

    @Test fun theBestScoreIsTheLargestShareAcrossAttempts() {
        assertNull(Notebook(title = "x").bestScore)
        val note = Notebook(title = "x", attempts = listOf(
            ExamAttempt(id = "a", score = 20, total = 40),
            ExamAttempt(id = "b", score = 33, total = 40)
        ))
        assertEquals(.825f, note.bestScore!!, .0001f)
    }
}

class ExamSetTests {
    private val set = ExamSet(id = "s1", name = "", subject = VceSubject.MATHS_METHODS, year = 2022, company = "VCAA", type = ExamType.EXAM_1)

    @Test fun membersAreFoundThroughTheNotebookLink() {
        val mine = Notebook(id = "a", title = "My attempt", setId = "s1")
        val solutions = Notebook(id = "b", title = "Solutions", setId = "s1")
        val other = Notebook(id = "c", title = "Unrelated")
        val groups = groupExamSets(listOf(set), listOf(mine, other, solutions))
        assertEquals(listOf("a", "b"), groups.single().notes.map { it.id })
    }

    @Test fun anEmptyNameFallsBackToTheTagLine() {
        assertEquals("Maths Methods · VCAA · 2022", set.autoName())
        assertEquals("My paper", set.copy(name = "My paper").autoName())
    }

    @Test fun scoresStaySeparateForEachPaper() {
        val attempts = Notebook(id = "a", title = "First go", setId = "s1", exam = ExamTags(type = ExamType.EXAM_1), attempts = listOf(ExamAttempt(id = "a1", score = 20, total = 40)))
        val redo = Notebook(id = "b", title = "Redo", setId = "s1", exam = ExamTags(type = ExamType.EXAM_2), attempts = listOf(ExamAttempt(id = "b1", score = 34, total = 40)))
        val group = groupExamSets(listOf(set), listOf(attempts, redo)).single()
        assertEquals(.5f, group.bestShare(ExamType.EXAM_1)!!, .0001f)
        assertEquals(.85f, group.bestShare(ExamType.EXAM_2)!!, .0001f)
        assertEquals(2, group.pairedPaperCount)
        assertEquals(2, group.attemptCount)
    }

    @Test fun matchingRequiresTheSameSubjectYearAndCompanyForEitherExam() {
        val paper = Notebook(title = "Paper", exam = ExamTags(subject = set.subject, year = 2022, company = " vcaa ", type = ExamType.EXAM_1))
        assertTrue(set.matchesPaper(paper))
        assertTrue(set.matchesPaper(paper.copy(exam = paper.exam.copy(type = ExamType.EXAM_2))))
        assertFalse(set.matchesPaper(paper.copy(exam = paper.exam.copy(year = 2021))))
        assertFalse(set.matchesPaper(paper.copy(exam = paper.exam.copy(company = "NEAP"))))
        assertFalse(set.matchesPaper(paper.copy(exam = paper.exam.copy(subject = VceSubject.PHYSICS))))
        assertFalse(set.matchesPaper(paper.copy(exam = paper.exam.copy(type = ExamType.SAC))))
        assertFalse(set.copy(subject = null).matchesPaper(paper))
    }

    @Test fun incompletePairsKeepSupportingNotebooksWithoutCountingThemAsPapers() {
        val paper = Notebook(title = "Exam 2", setId = set.id, exam = ExamTags(type = ExamType.EXAM_2))
        val supporting = Notebook(title = "Corrections", setId = set.id)
        val group = groupExamSets(listOf(set), listOf(supporting, paper)).single()
        assertEquals(1, group.pairedPaperCount)
        assertTrue(group.papers(ExamType.EXAM_1).isEmpty())
        assertEquals(listOf(paper), group.papers(ExamType.EXAM_2))
        assertNull(group.bestShare(ExamType.EXAM_2))
        assertEquals(2, group.notes.size)
    }

    @Test fun setsRoundTripThroughJson() {
        val sets = listOf(set.copy(name = "VCAA 2022 Exam 1", durationSeconds = 5400))
        val decoded = ExamTagsCodec.decodeSets(ExamTagsCodec.encodeSets(sets))
        assertEquals(sets, decoded)
    }
}

class SubjectProgressTests {
    @Test fun papersWithoutTagsAreLeftOut() {
        val plain = Notebook(title = "Ideas")
        assertTrue(subjectProgress(listOf(plain)).isEmpty())
    }

    @Test fun scoresRollUpPerSubject() {
        val methods = listOf(
            Notebook(title = "2021", exam = ExamTags(subject = VceSubject.MATHS_METHODS), attempts = listOf(ExamAttempt(id = "a", score = 20, total = 40))),
            Notebook(title = "2022", exam = ExamTags(subject = VceSubject.MATHS_METHODS), attempts = listOf(ExamAttempt(id = "b", score = 32, total = 40)))
        )
        val rows = subjectProgress(methods + Notebook(title = "Untagged"))
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(VceSubject.MATHS_METHODS, row.subject)
        assertEquals(2, row.paperCount)
        assertEquals(2, row.markedCount)
        assertEquals(.65f, row.averageShare!!, .0001f)
        assertEquals(.8f, row.bestShare!!, .0001f)
        assertEquals(.65f, row.recentShare!!, .0001f)
    }

    @Test fun theRecentAverageTakesEachPapersLatestMark() {
        val paper = Notebook(
            title = "2022", exam = ExamTags(subject = VceSubject.FURTHER_MATHS),
            attempts = listOf(ExamAttempt(id = "early", score = 10, total = 40, date = 1), ExamAttempt(id = "redo", score = 30, total = 40, date = 2))
        )
        val row = subjectProgress(listOf(paper)).single()
        assertEquals(.75f, row.recentShare!!, .0001f)
        assertEquals(.5f, row.averageShare!!, .0001f)
    }

    @Test fun papersWithoutASubjectRollUpTogether() {
        val untagged = Notebook(title = "Practice", exam = ExamTags(company = "TSSM"), attempts = listOf(ExamAttempt(id = "a", score = 8, total = 10)))
        val rows = subjectProgress(listOf(untagged))
        assertEquals(1, rows.size)
        assertNull(rows.single().subject)
        assertEquals(1, rows.single().paperCount)
    }
}

class ExamFilterTests {
    private val notes = listOf(
        Notebook(id = "a", title = "2022 Exam 1", exam = ExamTags(subject = VceSubject.MATHS_METHODS, year = 2022, company = "VCAA", type = ExamType.EXAM_1, status = ExamStatus.MARKED),
            attempts = listOf(ExamAttempt(id = "a1", score = 20, total = 40))),
        Notebook(id = "b", title = "2022 Exam 2", exam = ExamTags(subject = VceSubject.MATHS_METHODS, year = 2022, company = "VCAA", type = ExamType.EXAM_2)),
        Notebook(id = "c", title = "2021 Physics", exam = ExamTags(subject = VceSubject.PHYSICS, year = 2021, company = "NEAP"))
    )

    @Test fun fieldsCombineNarrowly() {
        assertEquals(listOf("a", "b"), organizeExams(notes, ExamFilter(subject = VceSubject.MATHS_METHODS)).map { it.id })
        assertEquals(listOf("a"), organizeExams(notes, ExamFilter(subject = VceSubject.MATHS_METHODS, type = ExamType.EXAM_1)).map { it.id })
        assertEquals(listOf("a", "b"), organizeExams(notes, ExamFilter(company = "vcaa")).map { it.id })
        assertEquals(listOf("a"), organizeExams(notes, ExamFilter(status = ExamStatus.MARKED)).map { it.id })
        assertEquals(emptyList<String>(), organizeExams(notes, ExamFilter(subject = VceSubject.MATHS_METHODS, year = 2021)).map { it.id })
    }

    @Test fun theLowScoreFilterCatchesOnlyPapersBelowTheBar() {
        assertEquals(listOf("a"), organizeExams(notes, ExamFilter(belowShare = .7f)).map { it.id })
        // Papers without a mark are not low scores; they are simply unmarked.
        assertEquals(emptyList<String>(), organizeExams(notes, ExamFilter(belowShare = 1f, subject = VceSubject.PHYSICS)).map { it.id })
    }

    @Test fun theRedoFilterFindsOnlyFlaggedPapers() {
        val flagged = notes.first().copy(pages = notes.first().pages.mapIndexed { i, page -> if (i == 0) page.copy(redoFlag = true) else page })
        assertEquals(emptyList<String>(), organizeExams(notes, ExamFilter(needsRedo = true)).map { it.id })
        assertEquals(listOf("a"), organizeExams(listOf(flagged, notes[1]), ExamFilter(needsRedo = true)).map { it.id })
    }

    @Test fun searchReachesTheTagsAsWellAsTheTitle() {
        assertEquals(listOf("a", "b"), organizeExams(notes, query = "2022").map { it.id })
        assertEquals(listOf("a"), organizeExams(notes, query = "exam 1").map { it.id })
        assertEquals(listOf("c"), organizeExams(notes, query = "NEAP").map { it.id })
        assertEquals(listOf("a", "b"), organizeExams(notes, query = "methods").map { it.id })
    }

    @Test fun setIdNarrowsToTheSetsMembers() {
        val member = notes.first().copy(setId = "s1")
        assertEquals(listOf(member.id), organizeExams(listOf(member, notes[1]), setId = "s1").map { it.id })
        assertEquals(emptyList<String>(), organizeExams(listOf(member), setId = "gone").map { it.id })
    }
}

class NotebookTemplateTests {
    @Test fun thePastExamTemplateSetsTheTagsAndManyPages() {
        val template = NotebookTemplate.pastExam()
        assertEquals("past-exam", template.id)
        assertTrue(template.pages > 1)
        val tags = template.tags(2022, "VCAA")
        assertEquals(ExamType.EXAM_1, tags.type)
        assertEquals(2022, tags.year)
        assertEquals("VCAA", tags.company)
        assertEquals(ExamStatus.TO_DO, tags.status)
    }

    @Test fun theSummaryTemplateIsBoundReferenceNotes() {
        val tags = NotebookTemplate.summary().tags(null, "")
        assertEquals(ExamType.NOTES, tags.type)
        assertTrue(ExamTagType.BOUND_REFERENCE in tags.tags)
    }

    @Test fun templatesLookUpByIdAndIgnoreUnknownNames() {
        assertEquals("mc-sheet", NotebookTemplate.byId("mc-sheet")?.id)
        assertNull(NotebookTemplate.byId("nope"))
        assertNull(NotebookTemplate.byId(null))
        assertEquals(NotebookTemplate.ALL.size, 4)
    }
}

class ExamTimerTests {
    private val preset = ExamTimerPreset("Test", 60 * 60, 15 * 60)

    @Test fun startingGoesStraightIntoReadingTime() {
        val started = ExamTimerState().start(preset, now = 1_000_000L)
        assertEquals(ExamTimerPhase.READING, started.phase)
        assertEquals(15 * 60, started.remaining)
        assertTrue(started.running)
    }

    @Test fun readingRollsIntoWritingAtTheHalfwayPoint() {
        val started = ExamTimerState().start(preset, now = 0L)
        val midReading = started.tick(now = 5 * 60 * 1000L)
        assertEquals(ExamTimerPhase.READING, midReading.phase)
        assertEquals(10 * 60, midReading.remaining)
        val firstWriting = started.tick(now = (15 * 60 + 30) * 1000L)
        assertEquals(ExamTimerPhase.WRITING, firstWriting.phase)
        assertEquals(60 * 60 - 30, firstWriting.remaining)
    }

    @Test fun theClockStopsAtPensDown() {
        val started = ExamTimerState().start(preset, now = 0L)
        val over = started.tick(now = (20 * 60 + 90 * 60 + 42) * 1000L)
        assertEquals(ExamTimerPhase.DONE, over.phase)
        assertEquals(0, over.remaining)
        // A done timer no longer ticks.
        assertEquals(over, over.tick(now = 1_000L))
    }

    @Test fun aPresetWithoutReadingTimeSkipsStraightToWriting() {
        val writing = ExamTimerPreset("No reading", 30 * 60, 0)
        val started = ExamTimerState().start(writing, now = 0L)
        assertEquals(ExamTimerPhase.WRITING, started.phase)
        assertEquals(30 * 60, started.remaining)
        // A minute in, a minute has gone from the clock.
        assertEquals(30 * 60 - 60, started.tick(now = 60 * 1000L).remaining)
    }

    @Test fun aStoppedTimerIsInert() {
        val idle = ExamTimerState()
        assertEquals(idle, idle.tick(now = 999_999L))
        val stopped = ExamTimerState().start(preset, now = 0L).stop()
        assertEquals(ExamTimerPhase.IDLE, stopped.phase)
        assertEquals(0, stopped.remaining)
        assertEquals(stopped, stopped.tick(now = 1_000_000L))
    }

    @Test fun theClockReadsNaturally() {
        fun clock(remaining: Int) = ExamTimerState(phase = ExamTimerPhase.WRITING, remaining = remaining).clockText()
        assertEquals("5:00", clock(300))
        assertEquals("0:09", clock(9))
        assertEquals("1:28:03", clock(3600 + 28 * 60 + 3))
    }

    @Test fun presetsMatchTheRealExams() {
        assertEquals(90 * 60, ExamTimerPreset.METHODS_EXAM_1.writingSeconds)
        assertEquals(120 * 60, ExamTimerPreset.METHODS_EXAM_2.writingSeconds)
        assertEquals(15 * 60, ExamTimerPreset.METHODS_EXAM_1.readingSeconds)
    }

    @Test fun theTimeSpentWritingIsClampedToThePaper() {
        val started = ExamTimerState().start(preset, now = 0L)
        // Long after pens-down, the recorded time is the paper's writing time, not the wall clock.
        assertEquals(60 * 60, started.tick(now = 10L * 3600 * 1000).elapsedWriting(now = 10L * 3600 * 1000))
        // During reading time nothing counts as writing yet.
        assertEquals(0, started.tick(now = 5L * 60 * 1000).elapsedWriting(now = 5L * 60 * 1000))
        // Half an hour into writing, half an hour is on the clock.
        assertEquals(30 * 60, started.tick(now = (15 * 60 + 30 * 60) * 1000L).elapsedWriting(now = (15 * 60 + 30 * 60) * 1000L))
        assertEquals(0, ExamTimerState().elapsedWriting())
    }

    @Test fun aSittingResumesMidReadingAfterARestart() {
        val startedAt = 1_000_000L
        val restored = ExamTimerState.resume(preset, startedAt, now = startedAt + 5 * 60 * 1000L)!!
        assertEquals(ExamTimerPhase.READING, restored.phase)
        assertEquals(10 * 60, restored.remaining)
        assertTrue(restored.running)
        // The restored state keeps ticking exactly like one that never stopped.
        assertEquals(9 * 60 + 59, restored.tick(now = startedAt + 5 * 60 * 1000L + 1000L).remaining)
    }

    @Test fun aSittingResumesMidWritingWithTheClockCaughtUp() {
        val startedAt = 1_700_000_000_000L
        val restored = ExamTimerState.resume(preset, startedAt, now = startedAt + (15 * 60 + 10 * 60) * 1000L)!!
        assertEquals(ExamTimerPhase.WRITING, restored.phase)
        assertEquals(50 * 60, restored.remaining)
    }

    @Test fun aDeadlineThatPassedWhileClosedComesBackAsPensDown() {
        val startedAt = 1_700_000_000_000L
        val restored = ExamTimerState.resume(preset, startedAt, now = startedAt + (20 * 60 + 90 * 60 + 300) * 1000L)!!
        assertEquals(ExamTimerPhase.DONE, restored.phase)
        assertEquals(0, restored.remaining)
    }

    @Test fun nothingSaneRestoresToNothing() {
        assertNull(ExamTimerState.resume(null, 1000L))
        assertNull(ExamTimerState.resume(preset, null))
        assertNull(ExamTimerState.resume(preset, 0L))
        assertNull(ExamTimerState.resume(preset, -5L))
        // A start moment in the future is corrupt, not a sitting to resume.
        assertNull(ExamTimerState.resume(preset, 10_000L, now = 5_000L))
    }

    @Test fun aStaleSittingIsDroppedRatherThanResurrected() {
        val twoDaysAgo = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000
        assertNull(ExamTimerState.resume(preset, twoDaysAgo))
    }
}

class MultipleChoicePaperTests {
    @Test fun theAnswerSheetIsARecognisedPaperThatSurvivesTheCodecs() {
        val page = NotePage(paper = Paper.MC_SHEET)
        assertEquals(Paper.MC_SHEET, Paper.safeValueOf("MC_SHEET"))
        assertEquals(Paper.DOTS, Paper.safeValueOf("NONSENSE"))
        assertEquals(page, NoteCodec.decode(NoteCodec.encode(Notebook(title = "MC", pages = listOf(page)))).pages.single())
    }

    @Test fun theAnswerSheetIsNotMistakenForGridPaper() {
        assertFalse(Paper.MC_SHEET.isGrid)
        // Snap-to-grid has no meaning on an answer sheet.
        assertEquals(20f, Paper.MC_SHEET.gridSpacing)
    }
}
