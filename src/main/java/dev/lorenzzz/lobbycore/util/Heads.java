package dev.lorenzzz.lobbycore.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.object.ObjectContents;

/**
 * Teste dei giocatori inline nel testo: {@code <head:Nome>} disegna la faccia 2D della
 * skin direttamente nella riga.
 *
 * <p>Usa il componente {@code object} di tipo {@code player} (client 1.21.9+): il gioco
 * risolve la skin dal profilo, quindi funzionano sia {@code <head:%player%>} (risolto in
 * nome prima del parse) sia un nome fisso come {@code <head:KimTole03>}. Su client piu
 * vecchi la riga mostra uno spazio vuoto al posto della testa.
 *
 * <p>La testa e un carattere come gli altri: puo stare accanto al testo nella stessa
 * riga, nel titolo come nelle righe, e passa dagli stessi posti del resto
 * (scoreboard, tablist, menu, item).
 */
public final class Heads {

    private Heads() {}

    /** Il tag MiniMessage {@code <head:Nome>}, registrato da {@link dev.lorenzzz.lobbycore.C}. */
    public static TagResolver resolver() {
        return TagResolver.resolver("head", (args, context) -> {
            String name;
            try {
                name = args.pop().value();
            } catch (RuntimeException e) {
                // <head> senza argomento: meglio niente che un'eccezione di parse.
                name = "";
            }
            return Tag.inserting(head(name));
        });
    }

    /** La faccia di {@code name}, vuota se il nome e inutilizzabile. Non tira mai. */
    public static Component head(String name) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty()) return Component.empty();
        try {
            return Component.object(ObjectContents.playerHead(clean));
        } catch (RuntimeException e) {
            // Nome che non si puo trasformare in testa: meglio il nome scritto che il vuoto.
            return Component.text(clean);
        }
    }
}
