package com.boox.day.ui.messages

import kotlin.random.Random

/**
 * The light-hearted lines shown while BooxDay waits (AI features, voice, calendar sync).
 *
 * Rewritten 29 Oct 2026: RotatingFunMessage shows a new line every 4 s and most waits are 3-14 s,
 * so each line is about what is happening and readable at a glance (AiFeedbackMessagesTest:
 * 60 characters at most, no repeats). No song lyrics, no "my servers" (BooxDay has none: the AI
 * runs on the user's own OpenRouter account), nothing that sounds like the app got it wrong.
 */
object AiFeedbackMessages {

    // Shuffle queues for ALL message types - ensures no repeats until all messages shown once
    // Uses "shuffle bag" pattern: shuffle deck, draw until empty, reshuffle
    private val audioMessageQueue = mutableListOf<String>()
    private val ocrMessageQueue = mutableListOf<String>()
    private val breakdownMessageQueue = mutableListOf<String>()
    private val prioritizationMessageQueue = mutableListOf<String>()
    private val generalMessageQueue = mutableListOf<String>()
    private val syncingMessageQueue = mutableListOf<String>()
    private val syncCompleteMessageQueue = mutableListOf<String>()
    private val notesMatchingMessageQueue = mutableListOf<String>()

    // Voice: events, Voice to Text, voice tasks
    private val audioMessages = listOf(
        "Turning your words into ink...",
        "Taking dictation. No shorthand class needed...",
        "Writing it down so you don't have to...",
        "Catching every word. Politely skipping the 'umms'...",
        "Converting sound waves into something useful...",
        "Translating 'you know, that thing' into actual words...",
        "Decoding mumbles. Professionally...",
        "Ears on. Pen ready...",
        "Spelling 'Wednesday' right, so you don't have to...",
        "Getting it in writing...",
        "Your words are on their way to the page...",
        "Listening like a cat to a can opener...",
        "Houston, we have audio...",
        "Adding commas where you took a breath...",
        "Your personal stenographer is typing..."
    )

    // Reading handwriting: Recognize Handwriting, task recognition, Break Down's first step
    private val ocrMessages = listOf(
        "Reading your handwriting. Squinting slightly...",
        "Is that an 'a' or an 'o'? Taking a closer look...",
        "Deciphering. Your doctor would be proud...",
        "Turning ink into letters, one loop at a time...",
        "Reading between the lines. And on them...",
        "Uncurling the cursive...",
        "Promoting your ink to text...",
        "Finding the words hiding in your strokes...",
        "Dotting your i's. Crossing your t's...",
        "Reading your notes faster than you wrote them...",
        "One does not simply read cursive...",
        "These are the letters you're looking for...",
        "Elementary, my dear... is that a 7 or a 1?",
        "Somewhere, a calligraphy teacher is smiling...",
        "A Rosetta Stone for your scribbles...",
        "Treating every squiggle with respect..."
    )

    // Break Down Task
    private val breakdownMessages = listOf(
        "Cutting this elephant into bite-sized pieces...",
        "Big task, meet small steps...",
        "Turning 'someday' into 'step one'...",
        "Slicing it like a pizza. Fair slices only...",
        "Finding the first domino...",
        "Turning a mountain into a few gentle hills...",
        "Every journey starts with a subtask...",
        "You're gonna need a smaller task...",
        "Hasta la vista, big task...",
        "Dividing and conquering. Mostly dividing...",
        "Taking the 'over' out of 'overwhelming'...",
        "From 'ugh' to 'okay, I can do that'...",
        "Unpacking this task like a suitcase...",
        "Writing the instructions your task never came with...",
        "Turning one big brick into Lego..."
    )

    // Sort by Priority (runs on the tablet)
    private val prioritizationMessages = listOf(
        "Sorting hat says... this one first!",
        "Deciding what matters. No pressure...",
        "First things first. Literally...",
        "Putting the important stuff on top...",
        "Ranking your tasks. No hurt feelings...",
        "Moving 'urgent' ahead of 'whenever'...",
        "There can be only one... at the top!",
        "Giving your A tasks the window seat...",
        "Someone has to go first. Picking who...",
        "Tidying your list like a bookshelf...",
        "The C tasks are being very patient about this...",
        "VIP tasks to the front, please...",
        "Lining up your tasks. Most important first..."
    )

    // Calendar sync messages - hamster + calendar/time themed
    private val syncingMessages = listOf(
        // Hamster + calendar context
        "Hamsters are fetching your meetings...",
        "Hamsters are rounding up your events...",
        "The hamsters found your calendar. Now they're reading it...",
        "Hamsters are double-checking your time zones...",
        "Hamsters are arguing about which event comes first...",
        "The hamsters counted your meetings. They need a nap.",
        "Hamsters are untangling your schedule...",
        "One hamster on Google, one on Outlook. Teamwork.",
        // Calendar/time puns
        "Syncing your future... please hold.",
        "Teaching your calendars to get along...",
        "Your events are en route. Traffic was light.",
        "Downloading your commitments... no take-backs.",
        "Gathering your appointments from the cloud...",
        "Time is an illusion. Your calendar is not.",
        "Making sure 3 PM means 3 PM everywhere..."
    )

    private val syncCompleteMessages = listOf(
        // Hamster + calendar
        "Hamsters delivered your calendar. Tips appreciated.",
        "Synced. The hamsters say it wasn't easy.",
        "All events accounted for. Hamsters are napping.",
        "Hamsters have aligned your calendars. They're proud.",
        "All caught up. Hamsters are on a coffee break.",
        // Calendar/time puns
        "Your calendars are now in perfect harmony.",
        "Past, present and future: all synced.",
        "Your schedule has landed.",
        "All your events are exactly where they should be.",
        "Calendars synced. The timeline is intact.",
        "Events synchronized. Chaos averted."
    )

    // Notes-to-events matching messages (Smart Link feature; no screen uses them yet)
    private val notesMatchingMessages = listOf(
        "Matchmaking your notes and your events...",
        "Finding where each note belongs...",
        "Connecting the dots between notes and meetings...",
        "Reuniting notes with their meetings...",
        "Reading titles for clues...",
        "One note to match them all...",
        "Elementary, my dear... this note goes with that event..."
    )

    // Any other AI wait (AiProcessingDialog)
    private val generalMessages = listOf(
        "Asking a very smart model very nicely...",
        "Thinking. Faster than e-ink refreshes, promise...",
        "Somewhere, a GPU is working hard for you...",
        "Brewing something useful...",
        "Thinking very hard. Look at it go...",
        "Do not disturb. Genius at work. Allegedly...",
        "Almost there. Probably. Definitely, maybe...",
        "Making it look easy...",
        "The model is thinking. Try to look impressed...",
        "Turning electricity into answers...",
        "Rome wasn't built in a day. This takes seconds...",
        "Don't panic. Bring a towel...",
        "Make it so...",
        "The answer is out there...",
        "Hang tight. Good stuff takes a moment...",
        "Consulting the digital oracle...",
        "Your request is in very capable circuits..."
    )

    /** Every list, by name (AiFeedbackMessagesTest). */
    internal fun allMessageSets(): Map<String, List<String>> = mapOf(
        "audio" to audioMessages, "ocr" to ocrMessages, "breakdown" to breakdownMessages,
        "prioritization" to prioritizationMessages,
        "syncing" to syncingMessages, "syncComplete" to syncCompleteMessages,
        "notesMatching" to notesMatchingMessages, "general" to generalMessages
    )

    // ========================================================================
    // Shuffle Bag Getters - No repeats until all messages in category shown
    // ========================================================================

    fun getRandomAudioMessage(): String {
        if (audioMessageQueue.isEmpty()) {
            audioMessageQueue.addAll(audioMessages.shuffled())
        }
        return audioMessageQueue.removeAt(0)
    }

    fun getRandomGeneralMessage(): String {
        if (generalMessageQueue.isEmpty()) {
            generalMessageQueue.addAll(generalMessages.shuffled())
        }
        return generalMessageQueue.removeAt(0)
    }

    fun getRandomOcrMessage(): String {
        if (ocrMessageQueue.isEmpty()) {
            ocrMessageQueue.addAll(ocrMessages.shuffled())
        }
        return ocrMessageQueue.removeAt(0)
    }

    fun getRandomBreakdownMessage(): String {
        if (breakdownMessageQueue.isEmpty()) {
            breakdownMessageQueue.addAll(breakdownMessages.shuffled())
        }
        return breakdownMessageQueue.removeAt(0)
    }

    fun getRandomPrioritizationMessage(): String {
        if (prioritizationMessageQueue.isEmpty()) {
            prioritizationMessageQueue.addAll(prioritizationMessages.shuffled())
        }
        return prioritizationMessageQueue.removeAt(0)
    }

    fun getRandomSyncingMessage(): String {
        if (syncingMessageQueue.isEmpty()) {
            syncingMessageQueue.addAll(syncingMessages.shuffled())
        }
        return syncingMessageQueue.removeAt(0)
    }

    fun getRandomSyncCompleteMessage(): String {
        if (syncCompleteMessageQueue.isEmpty()) {
            syncCompleteMessageQueue.addAll(syncCompleteMessages.shuffled())
        }
        return syncCompleteMessageQueue.removeAt(0)
    }

    fun getRandomNotesMatchingMessage(): String {
        if (notesMatchingMessageQueue.isEmpty()) {
            notesMatchingMessageQueue.addAll(notesMatchingMessages.shuffled())
        }
        return notesMatchingMessageQueue.removeAt(0)
    }
}
