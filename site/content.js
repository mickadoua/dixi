/* Copy by Léa (DMC-630). Add a language = add a key under i18n (and in ui.js). */
window.SITE = {
  name: 'Dixi', logo: 'logo.svg',
  repo: 'https://github.com/mickadoua/dixi',
  dmcPage: { en: 'https://www.dm-consulting.tech/en/open-source/dixi', fr: 'https://www.dm-consulting.tech/fr/open-source/dixi' },
  common: { license: 'Apache-2.0', licenseUrl: 'https://github.com/mickadoua/dixi/blob/main/LICENSE' },
  download: { play: 'https://play.google.com/store/apps/details?id=tech.dmconsulting.dixi', playLive: false, apk: { url: 'https://www.dm-consulting.tech/downloads/dixi.apk', sha256: '4a93bb1f5dcbbf948b4572a95099ef92c16198bf8aa7af19f7b1ce6f9982632a', size: '' } }, // APK v0.1.0 built by release.yml (DMC-658); playLive: true once the Play listing is public
  shots: [{ src: 'bulle-{lang}.jpg', wide: true }, { src: 'ecoute-{lang}.jpg', wide: true }, { src: 'suggestion-{lang}.jpg', wide: true }], // Play listing screenshots (DMC-462), one per language
  i18n: {
    en: { tagline: 'Voice dictation for Android', eyebrow: 'Android app · Apache-2.0',
      h1: 'Speak, and the text is <em>ready to paste.</em>',
      lede: 'Dixi is a floating bubble that turns your voice into text, in any Android app. Tap, speak, confirm: the text is copied.',
      shotCaptions: ['The bubble over another app', 'Listening', 'Suggested text to confirm'],
      featuresKicker: 'Features', featuresTitle: 'Tap, speak, confirm.', featuresIntro: 'No account. Your own Mistral key. Nothing to set up beyond that.',
      features: [
        { icon: 'mic', title: 'A bubble over your apps', text: 'Hide it with one gesture. An edge strip or a notification brings it back.' },
        { icon: 'text', title: 'Tidied text', text: 'Punctuation, paragraphs, filler words removed. The meaning does not change.' },
        { icon: 'keys', title: 'French or English', text: 'Detected automatically, or set in the settings. Up to 5 minutes per dictation.' }],
      highlights: { kicker: 'Details', title: 'Your key, your data.', intro: 'Dixi has no account and no server of its own.',
        chips: ['Voxtral', 'Mistral', 'French · English', 'Demo mode'],
        checks: [['Your own key.', 'Created at console.mistral.ai, stored encrypted on the phone, never in the app or the repository.'],
          ['Audio not kept.', 'Audio and text go through Mistral for transcription. Dixi never keeps the audio.'],
          ['Demo mode.', 'Without a key, the app shows sample text and sends nothing.']],
        facts: [['Platform', 'Android'], ['Transcription', 'Mistral (Voxtral)'], ['License', 'Apache-2.0']] },
      downloadTitle: 'Get Dixi', downloadIntro: 'Install the signed APK on an Android phone, after checking its SHA-256. The Google Play listing opens with the public release.',
      downloadNote: 'Needs the microphone, an Internet connection and permission to display over other apps. Mistral bills API usage under its own terms.' },
    fr: { tagline: 'Dictée vocale pour Android', eyebrow: 'Appli Android · Apache-2.0',
      h1: 'Parlez, le texte est <em>prêt à coller.</em>',
      lede: 'Dixi est une bulle flottante qui transforme votre voix en texte, dans n’importe quelle appli Android. Touchez, parlez, validez : le texte est copié.',
      shotCaptions: ['La bulle par-dessus une autre appli', 'Écoute', 'Texte proposé à valider'],
      featuresKicker: 'Fonctions', featuresTitle: 'Touchez, parlez, validez.', featuresIntro: 'Pas de compte. Votre propre clé Mistral. Rien d’autre à régler.',
      features: [
        { icon: 'mic', title: 'Une bulle par-dessus vos applis', text: 'Masquez-la d’un geste. Un liseré au bord de l’écran ou une notification la ramène.' },
        { icon: 'text', title: 'Texte mis en forme', text: 'Ponctuation, paragraphes, hésitations retirées. Le sens ne change pas.' },
        { icon: 'keys', title: 'Français ou anglais', text: 'Détection automatique, ou langue choisie dans les réglages. Jusqu’à 5 minutes par dictée.' }],
      highlights: { kicker: 'Détails', title: 'Votre clé, vos données.', intro: 'Dixi n’a ni compte ni serveur à lui.',
        chips: ['Voxtral', 'Mistral', 'Français · anglais', 'Mode démo'],
        checks: [['Votre propre clé.', 'Créée sur console.mistral.ai, stockée chiffrée sur le téléphone, jamais dans l’appli ni dans le dépôt.'],
          ['Audio non conservé.', 'L’audio et le texte passent par Mistral pour la transcription. Dixi ne garde jamais l’audio.'],
          ['Mode démo.', 'Sans clé, l’appli affiche un texte d’exemple et n’envoie rien.']],
        facts: [['Plateforme', 'Android'], ['Transcription', 'Mistral (Voxtral)'], ['Licence', 'Apache-2.0']] },
      downloadTitle: 'Obtenir Dixi', downloadIntro: 'Installez l’APK signé sur un téléphone Android, après avoir vérifié sa somme SHA-256. La fiche Google Play ouvre avec la version publique.',
      downloadNote: 'Demande le micro, une connexion Internet et l’autorisation d’afficher par-dessus les autres applis. Mistral facture l’usage de l’API selon ses conditions.' }
  }
};
