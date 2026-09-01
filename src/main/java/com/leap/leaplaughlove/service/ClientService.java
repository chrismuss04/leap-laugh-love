package com.leap.leaplaughlove.service;

import com.leap.leaplaughlove.entity.Client;
import com.leap.leaplaughlove.repository.ClientRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class ClientService {
    @Autowired
    private ClientRepository clientRepository;

    public Client createClient(Client client) {
        return clientRepository.save(client);
    }

    public Optional<Client> getClientById(UUID clientId) {
        return clientRepository.findById(clientId);
    }

    public Optional<Client> getClientByEmail(String email) {
        return clientRepository.findByEmail(email);
    }

    public List<Client> getAllClients() {
        return clientRepository.findAll();
    }

    public Client updateClient(UUID clientId, Client updatedClient) {
        return clientRepository.findById(clientId)
                .map(client -> {
                    if (updatedClient.getEmail() != null) {
                        client.setEmail(updatedClient.getEmail());
                    }
                    if (updatedClient.getPhone() != null) {
                        client.setPhone(updatedClient.getPhone());
                    }
                    if (updatedClient.getStatus() != null) {
                        client.setStatus(updatedClient.getStatus());
                    }
                    return clientRepository.save(client);
                })
                .orElseThrow(() -> new RuntimeException("Client not found with id: " + clientId));
    }

    public void deleteClient(UUID clientId) {
        clientRepository.deleteById(clientId);
    }
}
