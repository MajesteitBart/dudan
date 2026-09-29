// Android saves these component names when the user selects their digital assistant.
// Keep the entry points so an app update does not invalidate that selection.
package nl.bartvandermeeren.aight.assist

class AightVoiceInteractionService : nl.bartvandermeeren.dudan.assist.DudanVoiceInteractionService()
class AightSessionService : nl.bartvandermeeren.dudan.assist.DudanSessionService()
class AightRecognitionService : nl.bartvandermeeren.dudan.assist.DudanRecognitionService()
